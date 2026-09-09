(ns io.github.getcolors.once.describe-test
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.once.describe :as d]
   [io.github.getcolors.once.tools :as tools]))

(def ^:private base-opts
  {:profile "test"
   :provider-compute "digitalocean"
   :provider-backend "r2"
   :provider-smtp "resend"
   :provider-dns "cloudflare"
   :ip "203.0.113.10"
   :user "root"})

(defn- ok
  ([] (ok ""))
  ([out] {:ok? true :exit 0 :out out :err ""}))

(defn- fail
  [err]
  {:ok? false :exit 255 :out "" :err err})

(defn- remote-command
  [args]
  (let [target-idx (first (keep-indexed (fn [idx arg]
                                          (when (str/includes? arg "@") idx))
                                        args))]
    (vec (drop (inc target-idx) args))))

(defn- once-command-check
  []
  @#'d/once-command-check-args)

(deftest provider-summary-extracts-provider-names
  (is (= {:compute "digitalocean"
          :backend "r2"
          :smtp "resend"
          :dns "cloudflare"}
         (d/provider-summary base-opts))))

(deftest no-infra-compute-target-uses-configured-ip-and-user-when-state-is-missing
  (is (= {:ip "10.0.0.5" :user "ubuntu"}
         (#'d/compute-target {:provider-compute "no-infra"
                              :ip "192.168.0.1"
                              :no-infra-compute-ip "10.0.0.5"
                              :no-infra-compute-user "ubuntu"}))))

(deftest image-ref-parsing-handles-tags-defaults-and-registry-ports
  (is (= {:repository "ghcr.io/org/app"
          :tag "1.2.3"
          :image "ghcr.io/org/app:1.2.3"}
         (d/image->repository+tag "ghcr.io/org/app:1.2.3")))
  (is (= {:repository "ghcr.io/org/app"
          :tag "latest"
          :image "ghcr.io/org/app:latest"}
         (d/image->repository+tag "ghcr.io/org/app")))
  (is (= {:repository "localhost:5000/org/app"
          :tag "latest"
          :image "localhost:5000/org/app:latest"}
         (d/image->repository+tag "localhost:5000/org/app")))
  (is (= {:repository "localhost:5000/org/app"
          :tag "dev"
          :image "localhost:5000/org/app:dev"}
         (d/image->repository+tag "localhost:5000/org/app:dev"))))

(deftest once-list-parsing-strips-ansi-and-reads-status
  (let [output (str "\u001b[32mwww.example.com\u001b[0m (running)\n"
                    "forms.example.com (stopped)\n")]
    (is (= [{:host "www.example.com" :status "running"}
            {:host "forms.example.com" :status "stopped"}]
           (d/parse-once-list output)))))

(deftest docker-container-matching-uses-labels-and-names
  (let [containers [{:Name "/once-www-example-com"
                     :Config {:Image "ghcr.io/org/app:latest"
                              :Labels {:traefik.http.routers.app.rule "Host(`www.example.com`)"}}}
                    {:Name "/other"
                     :Config {:Image "ghcr.io/org/other:latest"}}]]
    (is (= "ghcr.io/org/app:latest"
           (get-in (#'d/find-container-for-host containers "www.example.com")
                   [:Config :Image])))))

(deftest docker-container-matching-prefers-the-once-label-host
  (testing "a bare host does not claim the container of a host it prefixes"
    (let [once-label (fn [host image]
                       {:Name (str "/once-app-" (str/replace host "." "-"))
                        :Config {:Image image
                                 :Labels {:once (json/generate-string
                                                 {:name "app" :image image :host host})}}})
          ;; the longer host is listed first, so a substring match returns it
          containers [(once-label "www.example.com" "ghcr.io/org/site:latest")
                      (once-label "example.com" "ghcr.io/org/redirect:latest")]]
      (is (= "ghcr.io/org/redirect:latest"
             (get-in (#'d/find-container-for-host containers "example.com")
                     [:Config :Image])))
      (is (= "ghcr.io/org/site:latest"
             (get-in (#'d/find-container-for-host containers "www.example.com")
                     [:Config :Image])))))

  (testing "a dotted host in a third-party label is boundary-checked"
    ;; the name carries no host, so the traefik rule is the only evidence; the
    ;; dot-substituted form (`example-com` inside `once-www-example-com`) stays
    ;; ambiguous by nature, which is why the `once` label is what actually decides
    (let [containers [{:Name "/app-1"
                       :Config {:Image "ghcr.io/org/site:latest"
                                :Labels {:traefik.http.routers.app.rule
                                         "Host(`www.example.com`)"}}}]]
      (is (some? (#'d/find-container-for-host containers "www.example.com")))
      (is (nil? (#'d/find-container-for-host containers "example.com"))))))

(deftest digest-selection-and-comparison
  (is (= "sha256:aaa"
         (d/matching-repo-digest "ghcr.io/org/app"
                                 ["ghcr.io/org/app@sha256:aaa"
                                  "ghcr.io/org/other@sha256:bbb"])))
  (is (false? (#'d/update-available? "sha256:aaa" "sha256:aaa")))
  (is (true? (#'d/update-available? "sha256:aaa" "sha256:bbb")))
  (is (nil? (#'d/update-available? nil "sha256:bbb"))))

(deftest tofu-output-passes-state-backend-credentials
  (testing "r2 state reads authenticate through the AWS chain"
    (let [calls  (atom [])
          run-fn (fn [args opts]
                   (swap! calls conj [args opts])
                   (ok (json/generate-string {:params {:value {:ip "203.0.113.10"}}})))
          opts   (assoc base-opts
                        :r2-access-key-id "r2-key"
                        :r2-secret-access-key "r2-secret")]
      (#'d/resolve-tofu-opts opts run-fn)
      (is (= 1 (count @calls)) "compute state uses the library; only SMTP uses tofu output")
      (is (every? (fn [[args _]] (= ["tofu" "output" "-json"] args)) @calls))
      (is (every? (fn [[_ o]] (= {"AWS_ACCESS_KEY_ID" "r2-key"
                                  "AWS_SECRET_ACCESS_KEY" "r2-secret"}
                                 (:extra-env o)))
                  @calls))))
  (testing "a local backend needs no credentials"
    (let [calls  (atom [])
          run-fn (fn [args opts]
                   (swap! calls conj [args opts])
                   (ok "{}"))]
      (#'d/resolve-tofu-opts (assoc base-opts :provider-backend "local") run-fn)
      (is (every? (fn [[_ o]] (nil? (:extra-env o))) @calls)))))

(deftest describe-ssh-failure-reports-unreachable-and-skips-remote-apps
  (let [calls  (atom [])
        run-fn (fn [args _opts]
                 (swap! calls conj args)
                 (when (some #{"once"} args)
                   (throw (ex-info "remote apps should not be checked" {})))
                 (fail "Permission denied"))
        result (d/describe-report base-opts run-fn)]
    (is (= :unreachable (get-in result [:compute :status])))
    (is (str/includes? (get-in result [:compute :detail]) "ssh failed"))
    (is (= [] (:applications result)))
    (is (= "not checked because compute is not reachable" (:applications-error result)))
    (is (= 1 (count @calls)))))

(deftest describe-without-compute-state-reports-absent-and-probes-nothing
  (testing "no outputs at all names the work directory"
    (let [calls  (atom [])
          run-fn (fn [args _opts]
                   (swap! calls conj args)
                   (ok "{}"))
          result (d/describe-report (dissoc base-opts :ip) run-fn)]
      (is (= :absent (get-in result [:compute :status])))
      (is (= (str "no OpenTofu state in " (tools/tool-dir base-opts "tofu-compute"))
             (get-in result [:compute :detail])))
      (is (= "not checked because compute has not been created"
             (:applications-error result)))
      (is (empty? @calls) "no ssh probe without an address")))
  (testing "a failed state read explains itself instead"
    (let [run-fn (fn [args _opts]
                   (if (= ["tofu" "output" "-json"] args)
                     (fail "Backend initialization required")
                     (throw (ex-info "unexpected command" {:args args}))))
          {:keys [compute-detail]} (#'d/resolve-tofu-opts (dissoc base-opts :ip) run-fn)
          result (#'d/report (dissoc base-opts :ip) run-fn {:compute-detail compute-detail})]
      (is (= :absent (get-in result [:compute :status])))
      (is (str/includes? (get-in result [:compute :detail])
                         "compute inventory unavailable")))))

(deftest describe-no-infra-host-is-never-absent
  (let [opts   (-> base-opts
                   (dissoc :ip)
                   (assoc :provider-compute "no-infra"))
        run-fn (fn [args _opts] (throw (ex-info "unexpected command" {:args args})))
        result (d/describe-report opts run-fn)]
    (is (= :unreachable (get-in result [:compute :status])))
    (is (= "no host configured" (get-in result [:compute :detail]))))
  (testing "a configured host is probed as usual"
    (let [opts   (-> base-opts
                     (dissoc :ip)
                     (assoc :provider-compute "no-infra"
                            :no-infra-compute-ip "10.0.0.5"))
          run-fn (fn [_args _opts] (fail "Connection refused"))
          result (d/describe-report opts run-fn)]
      (is (= :unreachable (get-in result [:compute :status])))
      (is (str/includes? (get-in result [:compute :detail]) "ssh failed")))))

(deftest describe-remote-command-failure-keeps-compute-running
  (let [run-fn (fn [args _opts]
                 (let [cmd (remote-command args)]
                   (cond
                     (= ["true"] cmd) (ok)
                     (= (once-command-check) cmd) (ok)
                     (= ["sudo" "-n" "once" "list"] cmd) (fail "once missing")
                     :else (throw (ex-info "unexpected command" {:args args})))))
        result (d/describe-report base-opts run-fn)]
    (is (= :running (get-in result [:compute :status])))
    (is (= [] (:applications result)))
    (is (false? (:fatal-error? result)))
    (is (str/includes? (:applications-error result) "once list failed"))))

(deftest describe-missing-remote-once-command-is-fatal
  (let [run-fn (fn [args _opts]
                 (let [cmd (remote-command args)]
                   (cond
                     (= ["true"] cmd) (ok)
                     (= (once-command-check) cmd) {:ok? false
                                                   :exit 127
                                                   :out ""
                                                   :err "once: command not found"}
                     :else (throw (ex-info "unexpected command" {:args args})))))
        result (d/describe-report base-opts run-fn)]
    (is (= :running (get-in result [:compute :status])))
    (is (= [] (:applications result)))
    (is (true? (:fatal-error? result)))
    (is (str/includes? (:applications-error result) "once command check failed"))))

(defn- describe-with
  [report]
  (with-redefs [d/describe-report (constantly (merge {:profile "test"
                                                      :providers {}
                                                      :applications []
                                                      :fatal-error? false}
                                                     report))]
    (let [result (atom nil)
          out (with-out-str (reset! result (d/describe base-opts)))]
      (assoc @result ::out out))))

(deftest describe-workflow-step-sets-exit-status
  (testing "a running host succeeds"
    (let [result (describe-with {:compute {:status :running :detail "ssh ok"}})]
      (is (= 0 (:green/exit result)))
      (is (false? (get-in result [::d/result :fatal-error?])))
      (is (str/includes? (::out result) "Status: running (ssh ok)"))))
  (testing "fatal report fails"
    (let [result (describe-with {:compute {:status :running} :fatal-error? true})]
      (is (= 1 (:green/exit result)))
      (is (= "describe failed" (:green/err result)))))
  (testing "a provisioned but unreachable host fails"
    (let [result (describe-with {:compute {:status :unreachable
                                           :detail "ssh failed (exit 255) — Connection refused"}})]
      (is (= 1 (:green/exit result)))
      (is (= "compute is unreachable — ssh failed (exit 255) — Connection refused"
             (:green/err result)))
      (is (str/includes? (::out result) "Status: unreachable (ssh failed"))))
  (testing "a host that was never created fails and says so"
    (let [result (describe-with {:compute {:status :absent
                                           :detail "no OpenTofu state in .green/test/tofu-compute"}})]
      (is (= 1 (:green/exit result)))
      (is (= "compute is absent — no OpenTofu state in .green/test/tofu-compute"
             (:green/err result)))
      (is (str/includes? (::out result) "Status: absent (no OpenTofu state in")))))

(deftest describe-success-reports-image-digests-and-update-status
  (let [container {:Id "container-1"
                   :Name "/once-www-example-com"
                   :Image "sha256:local-image"
                   :Config {:Image "ghcr.io/org/app:latest"
                            :Labels {:traefik.http.routers.app.rule "Host(`www.example.com`)"}}
                   :State {:Status "running"}}
        image     {:Id "sha256:local-image"
                   :RepoTags ["ghcr.io/org/app:latest"]
                   :RepoDigests ["ghcr.io/org/app@sha256:old"]
                   :Architecture "amd64"
                   :Os "linux"}
        run-fn    (fn [args _opts]
                    (if (= "skopeo" (first args))
                      (do
                        (is (some #{"--override-os"} args))
                        (is (some #{"--override-arch"} args))
                        (is (some #{"docker://ghcr.io/org/app:latest"} args))
                        (ok (json/generate-string {:Digest "sha256:new"})))
                      (let [cmd (remote-command args)]
                        (cond
                          (= ["true"] cmd) (ok)
                          (= (once-command-check) cmd) (ok)
                          (= ["sudo" "-n" "once" "list"] cmd) (ok "www.example.com (running)\n")
                          (= ["sudo" "-n" "docker" "ps" "-q"] cmd) (ok "container-1\n")
                          (= ["sudo" "-n" "docker" "inspect" "--type" "container" "container-1"] cmd)
                          (ok (json/generate-string [container]))
                          (= ["sudo" "-n" "docker" "image" "inspect" "sha256:local-image" "ghcr.io/org/app:latest"] cmd)
                          (ok (json/generate-string [image]))
                          :else (throw (ex-info "unexpected command" {:args args}))))))
        result    (d/describe-report base-opts run-fn)
        app       (first (:applications result))]
    (is (nil? (:applications-error result)))
    (is (= "www.example.com" (:host app)))
    (is (= "ghcr.io/org/app:latest" (:image app)))
    (is (= "latest" (:version app)))
    (is (= "sha256:old" (:digest app)))
    (is (= "sha256:new" (:registry-digest app)))
    (is (true? (:new-version? app)))))
