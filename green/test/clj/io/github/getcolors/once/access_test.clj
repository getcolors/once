(ns io.github.getcolors.once.access-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [io.github.getcolors.once.access :as access]
            [io.github.getcolors.once.machine :as machine]
            [io.github.getcolors.once.tools :as tools]
            [io.github.getcolors.compute-node :as node]
            [io.github.getcolors.compute-ssh :as ssh]))

(def opts {:profile "unit" :workdir "/tmp/once-unit" :compute-api-version 2
           :provider-compute "digitalocean" :provider-backend "s3"
           :s3-bucket "once-tests" :s3-region "eu-west-1"
           :digitalocean-region "ams3" :digitalocean-size "s-1vcpu-1gb" :digitalocean-image "ubuntu"
           :compute-ssh-sources ["0.0.0.0/0"] :compute-http-sources ["0.0.0.0/0"]
           :once/ssh-resource machine/placeholder-resource})

(deftest scope-releases-profile-lock-on-failure
  (let [root (str (java.nio.file.Files/createTempDirectory "once-lock-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        opts (assoc opts :workdir root)]
    (try
      (is (thrown-with-msg? Exception #"expected failure"
            (access/scoped #(do (access/lock! opts) (throw (ex-info "expected failure" {}))))))
      (is (= :ok (access/scoped #(do (access/lock! opts) :ok))))
      (finally (doseq [file (reverse (file-seq (io/file root)))] (io/delete-file file true))))))

(deftest agent-is-dedicated-and-uses-public-identity
  (with-redefs [ssh/start-agent! (fn [resources _ register!]
                                 (is (= "COLORS_PAR_ONCE_SSH_PASSPHRASE" (get-in resources [0 :request :passphrase_env])))
                                 (is (fn? register!))
                                 {:socket "/run/dedicated.sock" :identities {(:reference machine/placeholder-resource) "/tmp/identity.pub"}})]
    (let [result (access/scoped #(access/agent-step opts))
          arguments (access/identity-args result)]
      (is (= "/tmp/identity.pub" (:ssh-private-key-path result)))
      (is (some #{"IdentityAgent=/run/dedicated.sock"} arguments))
      (is (some #{"ForwardAgent=no"} arguments))
      (is (some #{"ControlMaster=no"} arguments)))))

(deftest live-read-uses-refresh-resolver-while-delete-inspects
  (let [calls (atom []) opts (assoc opts :once/ssh-registration (machine/placeholder-registration opts))]
    (with-redefs [node/resolve-connection! (fn [& _] (swap! calls conj :resolve)
                                           {:status "ready" :params {:ip "203.0.113.8" :user "root" :name "observed-name"}})
                  node/compute-node! (fn [& _] (swap! calls conj :inspect) {:status "destroyed"})]
      (let [result (machine/load-inventory (assoc opts :green/event :ssh) {})]
        (is (= "observed-name" (:name result)))
        (is (= "203.0.113.8" (:ip result))))
      (is (:colors-compute/already-destroyed (machine/load-inventory (assoc opts :green/event :delete) {})))
      (is (= [:resolve :inspect] @calls)))))

(deftest retired-registration-is-only-a-read-placeholder
  (with-redefs [node/compute-registration! (fn [& _] {:status "destroyed"})]
    (let [result (access/registration-step (assoc opts :green/event :delete))]
      (is (:once/registration-destroyed result))
      (is (= "ready" (get-in result [:once/ssh-registration :status]))))
    (is (= 1 (:green/exit (access/registration-step (assoc opts :green/event :create :compute-require-existing-state true)))))))

(deftest runtime-secret-environment-is-an-explicit-allowlist
  (let [opts {:provider-smtp "resend" :once {:applications [{:env {:DATABASE_URL "database-url"}}]}}
        env {"COLORS_PAR_RESEND_PASSWORD" "smtp-secret" "COLORS_PAR_DATABASE_URL" "db-secret"
             "COLORS_PAR_ONCE_SSH_PASSPHRASE" "key-secret" "COLORS_PAR_GITHUB_TOKEN" "github-secret"}]
    (is (= {"ONCE_PAR_RESEND_PASSWORD" "smtp-secret" "ONCE_PAR_DATABASE_URL" "db-secret"}
           (tools/ansible-secret-env opts env)))))

(deftest library-options-excludes-scope-and-passphrase
  (is (= {:profile "unit"}
         (machine/library-options {:profile "unit" :once-ssh-passphrase "secret"
                                   :once/access-scope (fn []) :ssh-private-key-path "/tmp/identity.pub"}))))

(deftest external-key-paths-are-refused-before-runtime-sanitization
  (doseq [key [:ssh-key-path :ssh-private-key-path :ssh-public-key-path]]
    (is (= ["external SSH keys are outside the single-node contract"]
           (machine/errors (assoc opts key "/tmp/external-key"))))))

(deftest encrypted-install-preflights-config-and-never-starts-agent
  (with-redefs [access/install-lock! (constantly nil)]
  (let [calls (atom [])
        opts {:profile "p" :green/event :ssh-install :ip "203.0.113.5" :user "root"}
        export (fn [_ op] (swap! calls conj op)
                 {:status "installed" :private_key_file "/home/me/.ssh/once/p/identity"})
        config (fn [payload] (swap! calls conj payload) {:exit 0})
        result (access/install-step opts export config)]
    (is (= 0 (:green/exit result)))
    (is (:check_only (first @calls)))
    (is (= "install" (second @calls)))
    (is (= "/home/me/.ssh/once/p/identity" (:identity_file (last @calls))))
    (is (= [{:name "p" :ip "203.0.113.5" :user "root"}]
           (:ssh_hosts (last @calls))))
    (is (:installed (last @calls)))
    (reset! calls [])
    (is (= 1 (:green/exit (access/install-step opts export (constantly {:exit 1 :err "collision"})))))
    (is (empty? @calls)))))

(deftest uninstall-is-local-and-removes-config-before-key
  (let [calls (atom [])
        export (fn [_ operation] (swap! calls conj operation)
                 {:status (if (= operation "inspect") "installed" "removed")})
        config (fn [payload] (swap! calls conj (:block_state payload)) {:exit 0})]
    (with-redefs [access/lock! (constantly nil) access/install-lock! (constantly nil)]
      (is (= 0 (:green/exit (access/uninstall-step {:profile "p"} export config))))
      (is (= ["inspect" "absent" "remove"] @calls))
      (reset! calls [])
      (is (= 1 (:green/exit (access/uninstall-step {:profile "p"} export
                                                  (constantly {:exit 1 :err "unsafe config"})))))
      (is (= ["inspect"] @calls)))))

(deftest local-export-identity-is-inspected-only-at-runtime
  (with-redefs [access/install-lock! (constantly nil)]
  (with-redefs [access/export-operation
                (fn [_ operation]
                  (is (= "inspect" operation))
                  {:status "installed" :private_key_file "/private/encrypted"})]
    (is (= "/private/encrypted" (access/installed-identity {:green/event :create})))
    (is (nil? (access/installed-identity {:green/event :build})))
    (is (nil? (access/installed-identity {:green/dry-run true}))))
  (with-redefs [access/export-operation (fn [& _] {:status "absent"})]
    (is (nil? (access/installed-identity {}))))
  (with-redefs [access/export-operation (fn [& _] {:status "error" :error {:message "unowned collision"}})]
    (is (thrown? Exception (access/installed-identity {}))))))

(deftest export-planning-has-no-effects
  (doseq [opts [{:green/event :build} {:green/dry-run true}]
          step [access/install-step access/uninstall-step]]
    (is (= opts (step opts (fn [& _] (throw (Exception. "export called")))
                          (fn [& _] (throw (Exception. "config called"))))))))

(deftest offline-export-operation-binds-authority-without-cloud-secrets
  (let [calls (atom []) opts {:profile "p" :workdir "/tmp/once-test"
                              :once/ssh-resource machine/placeholder-resource}]
    (access/export-operation opts "inspect"
      (fn [_ request destination operation env]
        (swap! calls conj [request destination operation env]) {:status "absent"}))
    (let [[request destination operation env] (first @calls)]
      (is (= machine/placeholder-resource (:expected request)))
      (is (= "inspect" operation))
      (is (.endsWith destination "/.ssh/once/p"))
      (is (every? #{"PATH" "HOME" "TMPDIR"} (keys env)))
      (is (string? (get env "PATH"))))))


(deftest installation-lock-is-shared-across-workdirs-and-released
  (let [home (.getCanonicalPath (io/file (str (java.nio.file.Files/createTempDirectory "once-install-test" (make-array java.nio.file.attribute.FileAttribute 0)))))]
    (try
      (access/scoped
        #(do (access/install-lock! {:profile "p" :workdir "/one"} home)
             (is (thrown? Exception
                   (access/scoped (fn [] (access/install-lock! {:profile "p" :workdir "/two"} home)))))))
      (is (nil? (access/scoped #(access/install-lock! {:profile "p" :workdir "/two"} home))))
      (let [lock (.toPath (io/file home ".ssh" ".once-install-p.lock"))
            target (.toPath (io/file home "other"))]
        (java.nio.file.Files/delete lock)
        (spit (str target) "unrelated")
        (java.nio.file.Files/createLink lock target)
        (is (thrown? Exception (access/scoped #(access/install-lock! {:profile "p"} home)))))
      (finally (doseq [file (reverse (file-seq (io/file home)))] (io/delete-file file true))))))
