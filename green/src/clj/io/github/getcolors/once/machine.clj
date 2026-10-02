(ns io.github.getcolors.once.machine
  "ONCE's stable v2 singleton and application parameter adapter."
  (:require [cheshire.core :as json] [clojure.java.io :as io] [clojure.string :as str]
            [green.cli :as cli]
            [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-node :as node]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.compute-ssh :as ssh]))

(def node-id "once-compute")
(def state-filename "once-node-0.tfstate")
(defn planning? [opts] (or (= :build (:green/event opts)) (:green/dry-run opts)))
(defn sdk-workdir [opts]
  (-> (cli/stage-dir opts node-id) io/file .getAbsoluteFile .getParentFile .getParentFile .getCanonicalPath))
(defn library-options [opts]
  (into {} (remove (fn [[key _]] (or (namespace key) (#{:ssh-private-key-path :ssh-public-key-path :once-ssh-passphrase} key))) opts)))
(def placeholder-resource
  {:status "ready" :reference "ssh-resource:build-placeholder"
   :public_key "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
   :fingerprint "SHA256:kmYcvdi2GkPeWxB6XLjrZB8JHsy2Hm8luHMFp9GMvqk"})
(defn resource [opts]
  (or (:once/ssh-resource opts) (when (planning? opts) placeholder-resource)
      (throw (ex-info "SSH resource unavailable" {}))))
(defn ssh-request [opts]
  {:name "machine-access" :workdir (sdk-workdir opts) :passphrase_env "COLORS_PAR_ONCE_SSH_PASSPHRASE"})
(defn registration? [opts]
  (boolean (get-in compute/registry [:compute (keyword (:provider-compute opts)) :registration])))
(defn registration-request [opts]
  {:name "machine-access" :workdir (sdk-workdir opts)
   :state_filename "once-ssh-registration.tfstate" :ssh_resource (resource opts)})
(defn placeholder-registration [opts]
  {:status "ready" :reference "registration:build-placeholder" :provider (:provider-compute opts)
   :ssh_resource_reference (:reference (resource opts)) :fingerprint (:fingerprint (resource opts)) :id "0"})
(defn requirements [opts]
  {:egress "all" :private_filter false
   :ingress (mapv (fn [[id port]]
                   (let [suffix (if (= id "ssh") "ssh-sources" "http-sources")
                         sources (let [value (or (get opts (keyword (str "compute-" suffix))) (get opts (keyword (str (:provider-compute opts) "-" suffix))))]
                                   (if (string? value) (vec (remove str/blank? (str/split value #"[,\s]+"))) value))]
                     (when-not (seq sources) (throw (ex-info (str "compute-" suffix " is required") {})))
                     {:id id :protocol "tcp" :from_port port :to_port port :sources sources}))
                 [["ssh" 22] ["http" 80] ["https" 443]])})
(defn request [opts]
  (cond-> {:node_id node-id :state_filename state-filename :workdir (sdk-workdir opts)
           :ssh_resource (resource opts) :security (requirements opts)}
    (#{"hcloud" "vultr" "digitalocean"} (:provider-compute opts)) (assoc :network {:mode "none"})
    (registration? opts) (assoc :ssh_registration (or (:once/ssh-registration opts)
                                                     (when (planning? opts) (placeholder-registration opts))))))
(defn errors [opts]
  (cond
    (not= 2 (:compute-api-version opts)) ["compute-api-version must be 2; existing deployments must retain their pinned launchers"]
    (not (#{"r2" "s3"} (:provider-backend opts))) ["compute state requires an s3 or r2 backend"]
    (some #(contains? opts %) [:ssh-key-path :ssh-private-key-path :ssh-public-key-path])
    ["external SSH keys are outside the single-node contract"]
    :else (try
            (let [opts (assoc opts :green/dry-run true)]
              (ssh/ssh-plan (library-options opts) (ssh-request opts))
              (node/node-plan (library-options opts) (request opts)))
            [] (catch Exception e [(.getMessage e)]))))
(defn placeholder-key [opts]
  (str "/home/build-placeholder/compute/" (:profile opts) "/ssh/machine-access/identity.pub"))
(defn params [opts result]
  (let [data (:params result)]
    (assoc data :name (or (:name data) (str (:profile opts) "-" node-id)) :sudoer (or (:sudoer data) (:user data)) :ssh-keygen true
           :ssh-private-key-path (or (:ssh-private-key-path opts) (when (planning? opts) (placeholder-key opts)))
           :once/agent-socket (:once/agent-socket opts))))
(defn fallback-params [opts]
  (when-not (planning? opts) (throw (ex-info "compute inventory unavailable" {})))
  (params opts {:params {:node_id node-id :provider (:provider-compute opts) :ip "192.0.2.10"
                        :user (get-in compute/registry [:compute (keyword (:provider-compute opts)) :user])}}))
(defn failure-message [opts result]
  ;; colors-compute supplies an authored command prefix and sanitized stderr.
  ;; Never print raw argv, environment, stdout or the whole error object.
  (let [{:keys [message stage command executable exit_code stderr command_reason]} (:error result)
        tool (first command)
        detail (case command_reason
                 "executable_not_found" (if tool (str "Executable \"" tool "\" was not found on PATH.") "Required executable was not found on PATH.")
                 "process_start_failed" "Required command could not start."
                 "timeout" "Required command timed out."
                 (or message "compute lifecycle refused"))]
    (str/join "\n"
              (cond-> [(str (when (= :ssh (:green/event opts)) "Cannot prepare SSH access: ") detail)]
                (seq stage) (conj (str "Compute stage: " stage))
                (seq command) (conj (str "Command: " (str/join " " command)))
                (seq executable) (conj (str "Executable: " executable))
                (some? exit_code) (conj (str "Exit status: " (if (neg? exit_code) "unavailable" exit_code)))
                (seq stderr) (conj stderr)
                (= command_reason "executable_not_found")
                (conj (if (= tool "tofu") "Make OpenTofu (tofu) available on PATH and retry."
                          (if tool (str "Make " tool " available on PATH and retry.") "Make the required executable available on PATH and retry.")))))))
(defn failed-result [opts result]
  (assoc opts :green/exit 1 :green/err (failure-message opts result)))
(defn adopt [opts result]
  (let [data (params opts result)]
    (assoc (merge opts data) :once/compute-params data :colors-compute/node (:params result) :green/exit 0)))
(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by (fn [[key _]] (if (keyword? key) (subs (str key) 1) (str key))) value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))
(defn canonical-build! [opts result]
  (local/private-owned-directory! (sdk-workdir opts) (local/path (:directory result)))
  (doseq [[filename document] (:documents result)]
    (let [target (str (io/file (:directory result) filename))]
      (local/prepare! target)
      (local/write-atomic! target (str (compute-json document 0) "\n"))))
  (assoc result :status "built"))
(defn step [opts]
  (let [result (if (planning? opts)
                 (canonical-build! opts (node/node-plan (library-options opts) (request opts)))
                 (node/compute-node! (library-options opts) (request opts)
                                     (if (= :delete (:green/event opts)) "delete" "create")))]
    (case (:status result)
      "built" (let [data (fallback-params opts)] (assoc (merge opts data) :once/compute-params data :green/exit 0))
      "ready" (adopt opts result)
      "destroyed" (assoc opts :green/exit 0)
      (failed-result opts result))))
(defn load-inventory
  ([opts] (load-inventory opts (System/getenv)))
  ([opts env]
   (let [result (if (#{:describe :ssh} (:green/event opts))
                  (node/resolve-connection! (library-options opts) (request opts) env)
                  (node/compute-node! (library-options opts) (request opts) "inspect" env))]
     (case (:status result)
       "ready" (adopt opts result)
       "destroyed" (if (= :delete (:green/event opts))
                     (assoc opts :green/exit 0 :colors-compute/already-destroyed true)
                     (assoc opts :green/exit 1 :green/err "compute node is destroyed"))
       (failed-result opts result)))))
