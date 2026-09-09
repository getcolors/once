(ns io.github.getcolors.once.describe
  "Describe the active green desired state after provisioning.

  The report shows configured providers, compute status, and deployed ONCE
  applications. Compute is `absent` when OpenTofu holds no compute outputs,
  `unreachable` when it does but SSH fails, and `running` otherwise; anything
  but `running`, and a missing remote `once` command, marks the step failed."
  (:require [io.github.getcolors.once.machine :as machine]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [green.cli :as green-cli]
   [green.process :as process]
   [io.github.getcolors.once.ssh :as ssh]
   [io.github.getcolors.once.tools :as tools]))

;;; -------------------------------------------------------------- command helpers

(def ^:private run-timeout-ms 30000)
(def ^:private ssh-probe-timeout-ms 10000)
(def ^:private registry-timeout-ms 30000)

(defn- run
  ([args] (run args {}))
  ([args {:keys [timeout-ms]
          :or {timeout-ms run-timeout-ms}
          :as opts}]
   (process/run-with-timeout args (dissoc opts :timeout-ms) timeout-ms)))

(defn- trim-snippet [s]
  (let [s (some-> s str/trim)]
    (when-not (str/blank? s)
      (if (> (count s) 200) (str (subs s 0 200) "…") s))))

(defn- result-detail
  [label {:keys [exit out err]}]
  (let [snippet (or (trim-snippet err) (trim-snippet out))]
    (format "%s failed (exit %d)%s"
            label
            (or exit -1)
            (if snippet (str " — " snippet) ""))))

(defn- once-command-not-found?
  [{:keys [exit out err]}]
  (let [text (str/lower-case (str (or err "") "\n" (or out "")))]
    (or (= 127 exit)
        (str/includes? text "once: command not found")
        (str/includes? text "once: not found")
        (str/includes? text "command not found: once"))))

(defn- ssh-base-args
  [{:keys [ip user] :as compute}]
  (-> ["ssh"
       "-o" "BatchMode=yes"
       "-o" "ConnectTimeout=5"
       "-o" "StrictHostKeyChecking=accept-new"]
      (into (ssh/identity-args compute))
      (conj (str user "@" ip))))

(defn- ssh-run
  ([run-fn compute remote-args]
   (ssh-run run-fn compute remote-args run-timeout-ms))
  ([run-fn compute remote-args timeout-ms]
   (run-fn (into (ssh-base-args compute) remote-args)
           {:timeout-ms timeout-ms})))

(def ^:private once-command-check-args
  ["command" "-v" "once" ">/dev/null" "2>&1"
   "||" "test" "-x" "/usr/local/bin/once"
   "||" "{" "echo" "once:" "command" "not" "found" ">&2" ";" "exit" "127" ";" "}"])

;;; -------------------------------------------------------------- providers + compute

(defn provider-summary
  "Return configured provider names from merged params."
  [params]
  {:compute (:provider-compute params)
   :backend (:provider-backend params)
   :smtp    (:provider-smtp params)
   :dns     (:provider-dns params)})

(def ^:private placeholder-ip
  "The address tools/fallback-compute-params renders with; never a real host."
  "192.168.0.1")

(defn- compute-target
  [{:keys [provider-compute ip user sudoer no-infra-compute-ip
           no-infra-compute-user no-infra-compute-sudoer
           ssh-keygen ssh-private-key-path]}]
  (let [ip (if (and (= provider-compute "no-infra")
                    (or (str/blank? ip) (= placeholder-ip ip))
                    (not (str/blank? no-infra-compute-ip)))
             no-infra-compute-ip
             ip)]
    ;; The keygen identity travels with the target so every probe names the
    ;; machine key explicitly; in opt-out mode the target is unchanged.
    (cond-> {:ip ip
             :user (or (not-empty user)
                       (not-empty sudoer)
                       (when (= provider-compute "no-infra")
                         (or (not-empty no-infra-compute-user)
                             (not-empty no-infra-compute-sudoer)))
                       "root")}
      ssh-keygen (assoc :ssh-keygen ssh-keygen
                        :ssh-private-key-path ssh-private-key-path))))

(defn- compute-status
  "Classify compute as :running, :unreachable, or :absent.

  An address can only reach the report through the tofu-compute outputs, so its
  absence means the stage was never applied — except under `no-infra`, where
  OpenTofu creates nothing and desired state supplies the host itself."
  [run-fn params compute-detail]
  (let [external? (= "no-infra" (:provider-compute params))
        {:keys [ip] :as target} (compute-target params)]
    (cond
      (or (str/blank? ip) (= placeholder-ip ip))
      (if external?
        (assoc target :status :unreachable :detail "no host configured")
        (assoc target :status :absent
               :detail (or compute-detail
                           (str "no OpenTofu state in "
                                (tools/tool-dir params "tofu-compute")))))

      :else
      (let [{:keys [ok?] :as result} (ssh-run run-fn target ["true"] ssh-probe-timeout-ms)]
        (assoc target
               :status (if ok? :running :unreachable)
               :detail (if ok? "ssh ok" (result-detail "ssh" result)))))))

;;; -------------------------------------------------------------- once list parsing

(def ^:private host-status-rx
  #"([A-Za-z0-9](?:[A-Za-z0-9.-]{0,253}[A-Za-z0-9])?\.[A-Za-z]{2,})(?:\s+\(([^)]*)\))?")

(defn parse-once-list
  [output]
  (->> (str/split-lines (process/strip-ansi output))
       (keep (fn [line]
               (when-let [[_ host status] (re-find host-status-rx line)]
                 (cond-> {:host host}
                   (not (str/blank? status)) (assoc :status status)))))
       vec))

;;; -------------------------------------------------------------- image helpers

(defn image->repository+tag
  "Parse a Docker image reference into repository, tag and normalized image.

  References without an explicit tag default to `latest`. Registry ports are
  handled by looking for a tag separator only after the final slash."
  [image]
  (let [image (some-> image str str/trim)]
    (when-not (or (str/blank? image)
                  (re-matches #"^sha256:[A-Fa-f0-9]+$" image))
      (let [without-digest (first (str/split image #"@" 2))
            last-slash     (.lastIndexOf ^String without-digest "/")
            last-colon     (.lastIndexOf ^String without-digest ":")
            has-tag?       (> last-colon last-slash)
            repository     (if has-tag?
                             (subs without-digest 0 last-colon)
                             without-digest)
            tag            (if has-tag?
                             (subs without-digest (inc last-colon))
                             "latest")]
        {:repository repository
         :tag tag
         :image (str repository ":" tag)}))))

(defn matching-repo-digest
  "Return the digest (e.g. `sha256:...`) from `repo-digests` for repository."
  [repository repo-digests]
  (some (fn [repo-digest]
          (let [[repo digest] (str/split (str repo-digest) #"@" 2)]
            (when (= repository repo) digest)))
        repo-digests))

(defn- update-available?
  [running-digest registry-digest]
  (when (and (not (str/blank? running-digest))
             (not (str/blank? registry-digest)))
    (not= running-digest registry-digest)))

(defn- registry-digest
  [run-fn image os arch]
  (let [args   (cond-> ["skopeo" "inspect" "--no-tags"]
                 (not (str/blank? os))   (into ["--override-os" os])
                 (not (str/blank? arch)) (into ["--override-arch" arch])
                 true                    (conj (str "docker://" image)))
        result (run-fn args {:timeout-ms registry-timeout-ms})]
    (if (:ok? result)
      (try
        {:digest (:Digest (json/parse-string (:out result) keyword))}
        (catch Exception e
          {:digest nil
           :detail (str "registry response was not valid JSON: " (.getMessage e))}))
      {:digest nil
       :detail (result-detail "skopeo inspect" result)})))

;;; -------------------------------------------------------------- docker parsing + matching

(defn- parse-json-vector
  [s]
  (try
    (let [v (json/parse-string (if (str/blank? s) "[]" s) keyword)]
      (if (sequential? v) (vec v) []))
    (catch Exception _
      [])))

(defn- string-leaves
  [x]
  (cond
    (nil? x) []
    (string? x) [x]
    (keyword? x) [(cond-> (name x)
                    (namespace x) (str "/" (namespace x)))]
    (map? x) (mapcat (fn [[k v]] (concat (string-leaves k) (string-leaves v))) x)
    (sequential? x) (mapcat string-leaves x)
    :else [(str x)]))

(defn- host-variants
  [host]
  (let [host (str/lower-case host)]
    (distinct [host
               (str/replace host "." "-")
               (str/replace host "." "_")])))

(defn- host-variant-rx
  "Match `variant` only where a longer hostname does not continue through it.

  `getcolors.ai` is a substring of `www.getcolors.ai`, so a plain `includes?`
  lets the shorter host claim the longer host's container. Dots and alphanumerics
  block a match; `-` and `_` do not, because container names embed a
  dot-substituted host inside a longer name (`/once-www-example-com`)."
  [variant]
  (re-pattern (str "(?<![a-z0-9.])"
                   (java.util.regex.Pattern/quote variant)
                   "(?![a-z0-9.])")))

(defn- once-label-host
  "The host ONCE recorded on the container, from its `once` label.

  ONCE writes the application's desired state there as JSON, making this the
  authoritative container-to-host mapping; everything else is inference from
  names and third-party labels."
  [container]
  (let [raw (str (get-in container [:Config :Labels :once]))]
    (when-not (str/blank? raw)
      (try
        (some-> (json/parse-string raw keyword)
                :host
                str
                str/trim
                not-empty
                str/lower-case)
        (catch Exception _ nil)))))

(defn- container-search-text
  [container]
  (->> (select-keys container [:Name :Config :NetworkSettings])
       string-leaves
       (str/join "\n")
       str/lower-case))

(defn- container-for-host?
  [host container]
  (let [text (container-search-text container)]
    (some #(re-find (host-variant-rx %) text) (host-variants host))))

(defn- find-container-for-host
  "Resolve the container serving `host`.

  The `once` label wins outright: a labelled container belongs to exactly the
  host it names, so it can never be claimed by another. Only containers ONCE did
  not label fall through to matching on names and third-party labels."
  [containers host]
  (let [host (str/lower-case host)]
    (or (some #(when (= host (once-label-host %)) %) containers)
        (some #(when (and (nil? (once-label-host %))
                          (container-for-host? host %))
                 %)
              containers))))

(defn- image-identifiers
  [containers]
  (->> containers
       (mapcat (fn [container]
                 [(:Image container)
                  (get-in container [:Config :Image])]))
       (remove str/blank?)
       distinct
       vec))

(defn- image-info-for-container
  [image-infos container parsed-image]
  (let [image-id    (:Image container)
        image-ref   (get-in container [:Config :Image])
        normalized  (:image parsed-image)
        repository  (:repository parsed-image)]
    (or (some #(when (= image-id (:Id %)) %) image-infos)
        (some #(when (contains? (set (:RepoTags %)) image-ref) %) image-infos)
        (some #(when (and normalized (contains? (set (:RepoTags %)) normalized)) %) image-infos)
        (some #(when (and repository (matching-repo-digest repository (:RepoDigests %))) %) image-infos))))

(defn- application-report
  [run-fn containers image-infos {:keys [host status]}]
  (let [container         (find-container-for-host containers host)
        image-ref         (some-> container (get-in [:Config :Image]))
        parsed-image      (image->repository+tag image-ref)
        image-info        (when container (image-info-for-container image-infos container parsed-image))
        os                (or (:Os image-info) "linux")
        arch              (or (:Architecture image-info) "amd64")
        running-digest    (when parsed-image
                            (matching-repo-digest (:repository parsed-image)
                                                  (:RepoDigests image-info)))
        fallback-digest   (or (:Id image-info) (:Image container))
        registry          (when parsed-image
                            (registry-digest run-fn (:image parsed-image) os arch))
        registry-digest'  (:digest registry)]
    (cond-> {:host host
             :status (or (some-> container (get-in [:State :Status]))
                         status
                         "unknown")
             :image (or (:image parsed-image) image-ref)
             :version (:tag parsed-image)
             :digest (or running-digest fallback-digest)
             :digest-source (cond
                              running-digest :repo-digest
                              fallback-digest :image-id)
             :registry-digest registry-digest'
             :new-version? (update-available? running-digest registry-digest')}
      (:detail registry) (assoc :registry-detail (:detail registry)))))

;;; -------------------------------------------------------------- remote discovery

(defn- remote-applications
  [run-fn compute]
  (let [{check-ok? :ok? :as once-check} (ssh-run run-fn compute once-command-check-args)]
    (if-not check-ok?
      {:ok? false
       :fatal? true
       :detail (result-detail "once command check" once-check)}
      (let [{:keys [ok? out] :as once-result}
            (ssh-run run-fn compute ["sudo" "-n" "once" "list"])]
        (if-not ok?
          (cond-> {:ok? false
                   :detail (result-detail "once list" once-result)}
            (once-command-not-found? once-result) (assoc :fatal? true))
          (let [once-apps (parse-once-list out)]
            (if (empty? once-apps)
              {:ok? true :applications []}
              (let [{:keys [ok? out] :as ps-result}
                    (ssh-run run-fn compute ["sudo" "-n" "docker" "ps" "-q"])]
                (if-not ok?
                  {:ok? false :detail (result-detail "docker ps" ps-result)}
                  (let [ids (->> (str/split-lines (or out ""))
                                 (map str/trim)
                                 (remove str/blank?)
                                 vec)]
                    (if (empty? ids)
                      {:ok? true
                       :applications (mapv #(assoc %
                                                   :status (or (:status %) "unknown")
                                                   :image nil
                                                   :version nil
                                                   :digest nil
                                                   :registry-digest nil
                                                   :new-version? nil)
                                           once-apps)}
                      (let [{:keys [ok? out] :as container-result}
                            (ssh-run run-fn compute
                                     (into ["sudo" "-n" "docker" "inspect" "--type" "container"] ids))]
                        (if-not ok?
                          {:ok? false :detail (result-detail "docker inspect" container-result)}
                          (let [containers (parse-json-vector out)
                                image-ids  (image-identifiers containers)]
                            (if (empty? image-ids)
                              {:ok? true
                               :applications (mapv #(application-report run-fn containers [] %) once-apps)}
                              (let [{:keys [ok? out] :as image-result}
                                    (ssh-run run-fn compute
                                             (into ["sudo" "-n" "docker" "image" "inspect"] image-ids))]
                                (if-not ok?
                                  {:ok? false :detail (result-detail "docker image inspect" image-result)}
                                  (let [image-infos (parse-json-vector out)]
                                    {:ok? true
                                     :applications (mapv #(application-report run-fn containers image-infos %)
                                                         once-apps)}))))))))))))))))))

;;; -------------------------------------------------------------- top-level

(defn- tofu-output-params
  [run-fn opts tool]
  (let [dir (tools/tool-dir opts tool)
        result (run-fn ["tofu" "output" "-json"]
                       {:dir dir
                        :extra-env (tools/backend-credential-env opts)
                        :timeout-ms run-timeout-ms})]
    (if (:ok? result)
      (try
        {:params (or (get-in (json/parse-string (:out result) keyword)
                             [:params :value])
                     {})}
        (catch Exception e
          {:detail (str tool " output was not valid JSON: " (.getMessage e))}))
      {:detail (result-detail (str "tofu output in " dir) result)})))

(defn- resolve-tofu-opts
  [opts run-fn]
  (let [loaded (machine/load-inventory opts)
        compute {:params (:once/compute-params loaded) :detail (:green/err loaded)}
        smtp (tofu-output-params run-fn opts "tofu-smtp")
        details (remove str/blank? [(:detail compute) (:detail smtp)])]
    {:opts (merge (dissoc opts :ip) (:params compute) (:params smtp))
     :detail (when (seq details) (str/join "; " details))
     :compute-detail (:detail compute)}))

(defn- report
  [opts run-fn {:keys [detail compute-detail]}]
  (let [providers (provider-summary opts)
        compute (compute-status run-fn opts compute-detail)
        ;; an absent compute already carries its own explanation
        compute (cond-> compute
                  (and detail (not= :absent (:status compute)))
                  (update :detail #(str % "; " detail)))
        app-result (case (:status compute)
                     :running (remote-applications run-fn compute)
                     :absent {:ok? false
                              :detail "not checked because compute has not been created"}
                     {:ok? false
                      :detail "not checked because compute is not reachable"})]
    {:profile (:profile opts)
     :providers providers
     :compute compute
     :applications (vec (:applications app-result))
     :applications-error (when-not (:ok? app-result) (:detail app-result))
     :fatal-error? (boolean (:fatal? app-result))}))

(defn describe-report
  "Build a describe report from flat green `opts`.

  The default arity reads compute and SMTP values from their OpenTofu state.
  The two-argument arity accepts an injected command runner and treats `opts`
  as already resolved, keeping report construction process-free in tests."
  ([opts]
   (let [{opts' :opts :as resolved} (resolve-tofu-opts opts run)]
     (report opts' run (dissoc resolved :opts))))
  ([opts run-fn]
   (report opts run-fn nil))
  ([opts run-fn opts-fn]
   (try
     (report (opts-fn opts) run-fn nil)
     (catch Exception e
       (let [detail (str "could not resolve OpenTofu parameters: " (.getMessage e))]
         (report opts run-fn {:detail detail :compute-detail detail}))))))

;;; -------------------------------------------------------------- reporting

(defn- present
  [x]
  (if (str/blank? (str x)) "unknown" (str x)))

(defn- update-label
  [x]
  (case x
    true "yes"
    false "no"
    nil "unknown"))

(defn- print-report
  [{:keys [profile providers compute applications applications-error]}]
  (println (format "Profile: %s" (present profile)))
  (println)
  (println "Providers:")
  (println (format "  Compute: %s" (present (:compute providers))))
  (println (format "  Backend: %s" (present (:backend providers))))
  (println (format "  SMTP: %s" (present (:smtp providers))))
  (println (format "  DNS: %s" (present (:dns providers))))
  (println)
  (println "Compute:")
  (println (format "  IP: %s" (present (:ip compute))))
  (println (format "  SSH user: %s" (present (:user compute))))
  (println (format "  Status: %s%s"
                   (name (or (:status compute) :unknown))
                   (if-let [detail (not-empty (:detail compute))]
                     (format " (%s)" detail)
                     "")))
  (println)
  (cond
    applications-error
    (println (format "Applications: %s." applications-error))

    (empty? applications)
    (println "Applications: none found.")

    :else
    (do
      (println "Applications:")
      (doseq [{:keys [host status image version digest digest-source registry-digest
                      new-version? registry-detail]} applications]
        (println (format "  - %s" (present host)))
        (println (format "    status: %s" (present status)))
        (println (format "    image: %s" (present image)))
        (println (format "    version: %s" (present version)))
        (println (format "    digest: %s%s"
                         (present digest)
                         (if (= digest-source :image-id) " (image id; digest comparison unknown)" "")))
        (println (format "    registry digest: %s" (present registry-digest)))
        (println (format "    update available: %s" (update-label new-version?)))
        (when registry-detail
          (println (format "    registry check: %s" registry-detail)))))))

(defn- compute-error
  "The failure message for compute that is anything but running."
  [{:keys [status detail]}]
  (when-not (= :running status)
    (format "compute is %s%s"
            (name (or status :unknown))
            (if-let [detail (not-empty detail)] (str " — " detail) ""))))

(defn describe
  "Print the report and return green's Unix-style outcome map."
  [opts]
  (let [result (describe-report opts)]
    (print-report result)
    (merge opts
           {::result result}
           (cond
             (:fatal-error? result)
             {:green/exit 1
              :green/err (or (:applications-error result) "describe failed")}

             (compute-error (:compute result))
             {:green/exit 1 :green/err (compute-error (:compute result))}

             :else {:green/exit 0}))))

(defn describe-file
  "Read a desired-state file, overlay `COLORS_PAR_*`, and describe the stack it
  names. Describing reads OpenTofu state and the host rather than changing
  either, so it runs outside the workflow and needs no validation gate."
  [path]
  (try
    (let [file (io/file path)]
      (if-not (.exists file)
        {:green/exit 2 :green/err (str "desired state file not found: " file)}
        (-> (green-cli/read-state file (slurp file))
            (assoc :green/state-file (.getAbsolutePath file))
            green-cli/read-pars
            ;; Describe reads the live host, so it needs the keygen identity
            ;; the way create does — real event semantics, opt-out untouched.
            (ssh/with-machine-key true)
            describe)))
    (catch Throwable t
      {:green/exit 2 :green/err (or (ex-message t) (str (class t)))})))
