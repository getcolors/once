(ns io.github.getcolors.once.workflow-test
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.ansible :as ansible]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.once.tools :as tools]
   [io.github.getcolors.once.workflow :as sut]))

(defn- temp-dir
  []
  (str (java.nio.file.Files/createTempDirectory
        "once-workflow-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-tree!
  [path]
  (doseq [f (reverse (file-seq (io/file path)))]
    (io/delete-file f true)))

(def ^:private valid
  {:profile "test"
   :workdir ".green"
   :once {:applications [{:host "www.example.com"
                          :image "ghcr.io/example/site:latest"}]}
   :provider-compute "no-infra"
   :provider-smtp "no-infra"
   :provider-dns "cloudflare"
   :provider-backend "local"
   :no-infra-compute-ip "203.0.113.10"
   :no-infra-compute-user "root"
   :no-infra-compute-sudoer "root"
   :no-infra-compute-uid "0"
   :no-infra-smtp-server "smtp.example.com"
   :no-infra-smtp-port 587
   :no-infra-smtp-username "user"})

;;; ------------------------------------------------------------------ start

(deftest start-refuses-to-run-on-invalid-state
  (let [result (sut/start-step (assoc valid :green/event :build :provider-dns "nope") {})]
    (is (= 2 (:green/exit result)))
    (is (str/includes? (:green/err result) "unsupported :provider-dns"))))

(deftest credentials-are-required-only-when-a-provider-will-be-reached
  (testing "create demands them"
    (let [result (sut/start-step (assoc valid :green/event :create) {})]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result)
                         "COLORS_PAR_NO_INFRA_SMTP_PASSWORD"))))

  (testing "build renders from desired state alone"
    (is (= 0 (:green/exit (sut/start-step (assoc valid :green/event :build) {})))))

  (testing "a dry run does not reach a provider either"
    (is (= 0 (:green/exit (sut/start-step (assoc valid
                                                 :green/event :create
                                                 :green/dry-run true)
                                          {})))))

  (testing "supplied through the environment, they satisfy the gate"
    (is (= 0 (:green/exit (sut/start-step (assoc valid :green/event :create)
                                          {"COLORS_PAR_NO_INFRA_SMTP_PASSWORD" "pw"
                                           "COLORS_PAR_CLOUDFLARE_API_TOKEN" "cf"}))))))

(deftest compute-destruction-is-protected-by-default
  (testing "delete stops before it starts"
    (let [result (sut/start-step (assoc valid :green/event :delete)
                                 {"COLORS_PAR_NO_INFRA_SMTP_PASSWORD" "pw"
                                  "COLORS_PAR_CLOUDFLARE_API_TOKEN" "cf"})]
      (is (= 2 (:green/exit result)))
      (is (str/includes? (:green/err result)
                         "COLORS_PAR_COMPUTE_PREVENT_DESTROY=false"))))

  (testing "the guard is on unless desired state says otherwise"
    (is (str/includes?
         (:green/err (sut/start-step (dissoc (assoc valid :green/event :delete)
                                             :compute-prevent-destroy)
                                     {"COLORS_PAR_NO_INFRA_SMTP_PASSWORD" "pw"
                                      "COLORS_PAR_CLOUDFLARE_API_TOKEN" "cf"}))
         "compute destruction is protected")))

  (testing "the environment override releases it, as a boolean not a string"
    (is (= 0 (:green/exit
              (sut/start-step (assoc valid :green/event :delete)
                              {"COLORS_PAR_NO_INFRA_SMTP_PASSWORD" "pw"
                               "COLORS_PAR_CLOUDFLARE_API_TOKEN" "cf"
                               "COLORS_PAR_COMPUTE_PREVENT_DESTROY" "false"})))))

  (testing "a dry-run delete needs no override — it destroys nothing"
    (is (= 0 (:green/exit (sut/start-step (assoc valid
                                                 :green/event :delete
                                                 :green/dry-run true)
                                          {}))))))

;;; ------------------------------------------------------------------ wiring

(defn- graph
  "The static successor graph wire-fn describes for an event. A step that has
  no place in this event's graph is absent rather than empty."
  [event]
  (into {}
        (keep (fn [step]
                (when-let [wired (try (sut/wire-fn step {:green/event event})
                                      (catch IllegalArgumentException _ nil))]
                  [step (vec (rest wired))])))
        (into [:once/start] sut/side-effecting-steps)))

(deftest create-forks-twice-and-joins-at-dns
  (let [g (graph :create)]
    (is (= [:once/tofu-compute :once/tofu-smtp] (:once/start g))
        "compute and smtp have no dependency on each other")
    (is (= [:once/tofu-dns] (:once/tofu-compute g)))
    (is (= [:once/tofu-dns] (:once/tofu-smtp g))
        "both branches converge, so dns joins them")
    (is (= [:once/tofu-smtp-post] (:once/tofu-dns g)))
    (is (= [:once/ansible-local :once/ansible-remote] (:once/tofu-smtp-post g)))
    (is (= [] (:once/ansible-local g)))
    (is (= [:once/github] (:once/ansible-remote g))
        "publishing follows the configured host, not the workstation")
    (is (= [] (:once/github g)))
    (testing "cleanup belongs to delete only"
      (is (nil? (:once/ansible-cleanup g))))))

(deftest delete-runs-the-stages-in-reverse
  (let [g (graph :delete)]
    (is (= [:once/github] (:once/start g))
        "credentials are withdrawn before anything is destroyed")
    (is (= [:once/ansible-cleanup] (:once/github g))
        "the managed SSH config goes before anything is destroyed")
    (is (= [:once/tofu-smtp-post] (:once/ansible-cleanup g)))
    (is (= [:once/tofu-dns] (:once/tofu-smtp-post g)))
    (is (= [:once/tofu-smtp :once/tofu-compute] (:once/tofu-dns g))
        "DNS must go before the records' targets")
    (is (= [] (:once/tofu-smtp g)))
    (is (= [] (:once/tofu-compute g)))))

(deftest build-follows-the-create-graph
  (is (= (graph :create) (graph :build))))

(deftest every-side-effecting-step-is-skipped-by-dry-run
  (let [plan-ids (fn [step]
                   (set (map :id (wf/advice-plan sut/workflow step))))]
    (doseq [step sut/side-effecting-steps]
      (is (contains? (plan-ids step) :green.dry-run/skip)
          (str step " would run during a dry run")))
    (testing "start is not skipped — validation has to happen"
      (is (not (contains? (plan-ids :once/start) :green.dry-run/skip))))))

;;; ------------------------------------------------------------------ backends

(defn- backend-json
  [dir]
  (json/parse-string (slurp (io/file dir "backend.tf.json"))))

(deftest backend-advice-writes-the-selected-backend-per-stage
  (let [workdir (temp-dir)
        opts (assoc valid :workdir workdir)
        dir #(tools/tool-dir opts %)]
    (try
      (testing "local is the default and needs no configuration"
        ((sut/backend-advice "tofu-dns") (dissoc opts :provider-backend))
        (is (= {"local" {}} (get-in (backend-json (dir "tofu-dns")) ["terraform" "backend"]))))

      (testing "remote state is keyed by profile and stage, so profiles never collide"
        ((sut/backend-advice "tofu-compute")
         (assoc opts :provider-backend "s3" :s3-bucket "b" :s3-region "eu-west-1"))
        ((sut/backend-advice "tofu-smtp")
         (assoc opts :provider-backend "s3" :s3-bucket "b" :s3-region "eu-west-1"))
        (is (= "test/tofu-compute.tfstate"
               (get-in (backend-json (dir "tofu-compute")) ["terraform" "backend" "s3" "key"])))
        (is (= "test/tofu-smtp.tfstate"
               (get-in (backend-json (dir "tofu-smtp")) ["terraform" "backend" "s3" "key"]))))

      (testing "r2 is an s3 backend, and its credentials stay out of the file"
        ((sut/backend-advice "tofu-dns")
         (assoc opts :provider-backend "r2"
                :r2-bucket "b" :r2-endpoint "https://acct.r2.cloudflarestorage.com"
                :r2-access-key-id "AKIA-SECRET" :r2-secret-access-key "SHHH"))
        (let [written (slurp (io/file (dir "tofu-dns") "backend.tf.json"))
              config (get-in (json/parse-string written) ["terraform" "backend" "s3"])]
          (is (= "auto" (get config "region")))
          (is (= {"s3" "https://acct.r2.cloudflarestorage.com"} (get config "endpoints")))
          (is (not (str/includes? written "AKIA-SECRET")))
          (is (not (str/includes? written "SHHH")))))

      (testing "an unsupported backend is an error, not a silent local one"
        (is (thrown? clojure.lang.ExceptionInfo
                     ((sut/backend-advice "tofu-dns") (assoc opts :provider-backend "azure")))))
      (finally
        (delete-tree! workdir)))))

;;; ------------------------------------------------------------------ end to end

(def ^:private expected-build-artifacts
  #{"tofu-compute/backend.tf.json" "tofu-compute/main.tf"
    "tofu-smtp/backend.tf.json" "tofu-smtp/main.tf"
    "tofu-dns/backend.tf.json" "tofu-dns/main.tf"
    "tofu-dns/apps.tf.json" "tofu-dns/smtp.tf.json"
    "tofu-smtp-post/backend.tf.json" "tofu-smtp-post/main.tf"
    "ansible-local/ansible.cfg" "ansible-local/inventory.ini" "ansible-local/main.yml"
    "ansible-remote/ansible.cfg" "ansible-remote/main.yml"
    "ansible-remote/inventory.json" "ansible-remote/once.yml"
    "ansible-remote/files/deploy" "ansible-remote/files/authorized-keys"
    "ansible-remote/deploy_keys" "ansible-remote/library/once"})

(deftest a-build-renders-the-whole-tree-and-runs-no-tool
  (let [workdir (temp-dir)]
    (try
      (with-redefs [tofu/tofu-step (fn [& _] (throw (ex-info "tofu must not run for build" {})))
                    ansible/ansible-step (fn [& _] (throw (ex-info "ansible must not run for build" {})))]
        (let [result (wf/run sut/workflow (assoc valid
                                                 :workdir workdir
                                                 :green/event :build))
              root (io/file workdir "test")
              rendered (->> (file-seq root)
                            (filter #(.isFile %))
                            (map #(str (.relativize (.toPath root) (.toPath %))))
                            set)]
          (is (= 0 (:green/exit result)) (:green/err result))
          (is (= expected-build-artifacts rendered))))
      (finally
        (delete-tree! workdir)))))

(deftest a-build-with-yandex-dns-renders-the-generated-records
  (let [workdir (temp-dir)]
    (try
      (let [result (wf/run sut/workflow (assoc valid
                                               :workdir workdir
                                               :provider-dns "yandex"
                                               :yandex-cloud-id "cloud-id"
                                               :yandex-folder-id "folder-id"
                                               :green/event :build))
            dns (io/file (tools/tool-dir {:workdir workdir :profile "test"} "tofu-dns"))]
        (is (= 0 (:green/exit result)) (:green/err result))
        (is (str/includes? (slurp (io/file dns "main.tf")) "yandex_dns_zone"))
        (is (.exists (io/file dns "apps.tf.json")))
        (is (.exists (io/file dns "smtp.tf.json"))))
      (finally
        (delete-tree! workdir)))))

(deftest a-build-with-no-infra-dns-renders-no-generated-records
  (let [workdir (temp-dir)]
    (try
      (let [result (wf/run sut/workflow (assoc valid
                                               :workdir workdir
                                               :provider-dns "no-infra"
                                               :green/event :build))
            dns (io/file (tools/tool-dir {:workdir workdir :profile "test"} "tofu-dns"))]
        (is (= 0 (:green/exit result)) (:green/err result))
        (is (.exists (io/file dns "main.tf")))
        (is (not (.exists (io/file dns "apps.tf.json"))))
        (is (not (.exists (io/file dns "smtp.tf.json")))))
      (finally
        (delete-tree! workdir)))))

(deftest a-failing-start-stops-the-run-before-any-stage
  (let [workdir (temp-dir)]
    (try
      (with-redefs [tofu/tofu-step (fn [& _] (throw (ex-info "must not reach tofu" {})))]
        (let [result (wf/run sut/workflow (assoc valid
                                                 :workdir workdir
                                                 :provider-compute "nope"
                                                 :green/event :build))]
          (is (= 2 (:green/exit result)))
          (is (empty? (filter #(.isFile %) (file-seq (io/file workdir)))))))
      (finally
        (delete-tree! workdir)))))
