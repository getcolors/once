(ns io.github.getcolors.once.validate-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [io.github.getcolors.once.validate :as sut]))

(def ^:private valid
  {:profile "test"
   :workdir ".green"
   :once {:applications [{:host "www.example.com"
                          :image "ghcr.io/example/site:latest"}]}
   :provider-compute "no-infra"
   :provider-smtp "no-infra"
   :provider-dns "no-infra"
   :provider-backend "local"
   :compute-prevent-destroy true
   :no-infra-compute-ip "203.0.113.10"
   :no-infra-compute-user "root"
   :no-infra-compute-sudoer "root"
   :no-infra-compute-uid "0"
   :no-infra-smtp-server "smtp.example.com"
   :no-infra-smtp-port 587
   :no-infra-smtp-username "user"})

(deftest a-complete-desired-state-has-no-errors
  (is (= [] (sut/state-errors valid))))

(deftest every-provider-slot-must-name-a-known-provider
  (doseq [slot [:provider-compute :provider-smtp :provider-dns :provider-backend]]
    (testing (str slot " rejects an unknown name")
      (is (some #(str/includes? % (str "unsupported " slot))
                (sut/state-errors (assoc valid slot "nope")))))))

(deftest missing-provider-keys-are-reported-per-provider
  (testing "each compute provider asks only for its own keys"
    (let [errors (sut/state-errors (assoc valid :provider-compute "digitalocean"))]
      (is (some #(str/includes? % ":digitalocean-region") errors))
      (is (not-any? #(str/includes? % ":hcloud-") errors)))
    (let [errors (sut/state-errors (assoc valid :provider-compute "yandex"))]
      (is (some #(str/includes? % ":yandex-cloud-id") errors))
      (is (some #(str/includes? % ":compute-pubkey") errors))
      (is (not-any? #(str/includes? % ":oci-") errors))))

  (testing "resend needs no non-secret keys — its relay is hard-coded"
    (is (= [] (sut/state-errors (assoc valid :provider-smtp "resend")))))

  (testing "yandex DNS asks for the cloud and folder, not the compute keys"
    (let [errors (sut/state-errors (assoc valid :provider-dns "yandex"))]
      (is (some #(str/includes? % ":yandex-cloud-id") errors))
      (is (some #(str/includes? % ":yandex-folder-id") errors))
      (is (not-any? #(str/includes? % ":yandex-zone") errors))
      (is (not-any? #(str/includes? % ":compute-pubkey") errors)))))

(deftest placeholders-count-as-missing
  (doseq [v [nil "" "   " "REPLACE_ME" "replace_me"]]
    (is (true? (sut/placeholder? v)) (str "expected placeholder: " (pr-str v))))
  (is (false? (sut/placeholder? "real-value")))
  (testing "a REPLACE_ME left in the file is not a value"
    (is (some #(str/includes? % ":no-infra-smtp-server is required")
              (sut/state-errors (assoc valid :no-infra-smtp-server "REPLACE_ME"))))))

(deftest applications-are-validated-individually
  (testing "at least one is required"
    (is (some #(str/includes? % "non-empty")
              (sut/state-errors (assoc-in valid [:once :applications] []))))
    (is (some #(str/includes? % "non-empty")
              (sut/state-errors (dissoc valid :once)))))

  (testing "a host must be a domain name"
    (doseq [host ["localhost" "not a domain" "http://www.example.com" ""]]
      (is (some #(str/includes? % "invalid :host")
                (sut/state-errors (assoc-in valid [:once :applications 0 :host] host)))
          (str "expected rejection of " (pr-str host)))))

  (testing "an image is required"
    (is (some #(str/includes? % "requires :image")
              (sut/state-errors (update-in valid [:once :applications 0] dissoc :image)))))

  (testing "the index names which application is wrong"
    (let [errors (sut/state-errors
                  (update-in valid [:once :applications] conj {:host "bad host"}))]
      (is (some #(str/includes? % ":applications[1]") errors)))))

(deftest application-env-maps-names-to-keys-not-values
  (testing "a container variable name must be a shell-safe identifier"
    (is (some #(str/includes? % "invalid container variable name")
              (sut/state-errors (assoc-in valid [:once :applications 0 :env]
                                          {"not-valid" :some-key})))))

  (testing "the value side must name a key"
    (is (some #(str/includes? % "needs a colors.yml key")
              (sut/state-errors (assoc-in valid [:once :applications 0 :env]
                                          {"OK" nil})))))

  (testing "a list is passed through as written"
    (is (= [] (sut/state-errors (assoc-in valid [:once :applications 0 :env]
                                          ["TARGET=forms@example.com"])))))

  (testing "anything else is a mistake worth naming"
    (is (some #(str/includes? % ":env must map")
              (sut/state-errors (assoc-in valid [:once :applications 0 :env] "A=1"))))))

(deftest ssh-keys-are-checked-for-shape
  (testing ":compute-pubkey is optional outside Yandex, but a present value must look right"
    (is (= [] (sut/state-errors (assoc valid :compute-pubkey "ssh-rsa AAAA x"))))
    (is (= [] (sut/state-errors (assoc valid :compute-pubkey "REPLACE_ME"))))
    (is (some #(str/includes? % ":compute-pubkey must be an SSH public key")
              (sut/state-errors (assoc valid :compute-pubkey "nope"))))))

(deftest prevent-destroy-must-be-a-boolean
  (is (some #(str/includes? % ":compute-prevent-destroy must be true or false")
            (sut/state-errors (assoc valid :compute-prevent-destroy "true")))))

(deftest secrets-are-demanded-per-selected-provider
  (testing "only the chosen providers' credentials are required"
    (let [errors (sut/secret-errors (assoc valid
                                           :provider-compute "digitalocean"
                                           :provider-dns "cloudflare"))]
      (is (= #{"required credential is not set: COLORS_PAR_DO_TOKEN"
               "required credential is not set: COLORS_PAR_CLOUDFLARE_API_TOKEN"
               "required credential is not set: COLORS_PAR_NO_INFRA_SMTP_PASSWORD"}
             (set errors)))))

  (testing "a supplied credential is not reported"
    (is (empty? (sut/secret-errors (assoc valid
                                          :provider-smtp "resend"
                                          :resend-api-key "re_x"
                                          :resend-password "pw")))))

  (testing "Yandex requires its API token"
    (is (= ["required credential is not set: COLORS_PAR_YANDEX_TOKEN"]
           (sut/secret-errors (assoc valid
                                     :provider-compute "yandex"
                                     :no-infra-smtp-password "pw")))))

  (testing "yandex compute and DNS share the token, and demand it once"
    (is (= ["required credential is not set: COLORS_PAR_YANDEX_TOKEN"]
           (sut/secret-errors (assoc valid
                                     :provider-compute "yandex"
                                     :provider-dns "yandex"
                                     :no-infra-smtp-password "pw")))))

  (testing "oci and the local backend need no credential of their own"
    (is (empty? (sut/secret-errors (assoc valid
                                          :provider-compute "oci"
                                          :no-infra-smtp-password "pw")))))

  (testing "r2 needs both halves of the AWS pair"
    (is (= #{"required credential is not set: COLORS_PAR_R2_ACCESS_KEY_ID"
             "required credential is not set: COLORS_PAR_R2_SECRET_ACCESS_KEY"}
           (set (sut/secret-errors (assoc valid
                                          :provider-backend "r2"
                                          :no-infra-smtp-password "pw")))))))

(deftest application-env-secrets-are-required-only-on-create
  (let [opts (-> valid
                 (assoc :no-infra-smtp-password "pw")
                 (assoc-in [:once :applications 0 :env] {"DATABASE_URL" :app-database-url}))]
    (testing "create needs the value the application will run with"
      (is (= ["required credential is not set: COLORS_PAR_APP_DATABASE_URL"]
             (sut/secret-errors (assoc opts :green/event :create)))))
    (testing "delete does not — it never reaches the application"
      (is (empty? (sut/secret-errors (assoc opts :green/event :delete)))))))

(deftest the-registry-is-the-only-source-of-credential-env-vars
  (testing "each provider's tofu-env is a subset of its secrets"
    (doseq [[slot providers] sut/providers
            [provider {:keys [secrets tofu-env]}] providers]
      (is (every? (set secrets) (keys tofu-env))
          (str slot "/" provider " exports a variable it never asks for"))))

  (testing "the selected provider decides what tofu sees"
    (is (= {:do-token "DIGITALOCEAN_TOKEN"}
           (sut/tofu-env {:provider-compute "digitalocean"} :provider-compute)))
    (is (= {:yandex-token "YC_TOKEN"}
           (sut/tofu-env {:provider-compute "yandex"} :provider-compute)))
    (is (= {} (sut/tofu-env {:provider-compute "no-infra"} :provider-compute))))

  (testing "the resend password is a secret tofu never receives"
    (let [{:keys [secrets tofu-env]} (get-in sut/providers [:provider-smtp "resend"])]
      (is (contains? (set secrets) :resend-password))
      (is (not (contains? tofu-env :resend-password))))))
