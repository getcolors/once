(ns io.github.getcolors.once.validate
  "The provider registry and the desired-state validation it drives.

  Every provider the four provider slots can be pointed at is described once,
  in `providers`: the non-secret keys its templates interpolate, the
  credentials it needs, and which of those credentials OpenTofu reads from the
  process environment. Splitting that across separate tables is how a provider
  ends up validated against one set of keys and run with another — a stage
  exporting a credential nobody checked for, or a check demanding a key no
  stage ever uses."
  (:require
   [clojure.string :as str]
   [green.cli :as green-cli]))

(def providers
  "Provider slot -> provider name -> what that choice implies.

  `:required` are non-secret keys desired state must supply, because a
  template interpolates them. `:secrets` are keys that must arrive through
  `COLORS_PAR_*` instead, and are never read from the file. `:tofu-env` is the
  subset of `:secrets` OpenTofu itself reads, mapped to the variable each
  provider looks for natively — passing them through the environment keeps
  them out of the rendered .tf files, which sit in the work directory in
  plaintext. A secret that is not in `:tofu-env` reaches its tool some other
  way: the SMTP passwords are looked up by Ansible at play time."
  {:provider-compute
   {"azure" {:required [:azure-subscription-id :azure-location
                          :azure-resource-group :azure-name :azure-vm-size
                          :azure-image-publisher :azure-image-offer
                          :azure-image-sku :azure-image-version :azure-vnet-cidr
                          :azure-subnet-cidr :azure-boot-disk-size-gb
                          :azure-ssh-authorized-keys]
              :secrets []
              :tofu-env {}}
    "aws" {:required [:aws-region :aws-availability-zone :aws-name
                       :aws-instance-type :aws-image-id :aws-vpc-cidr
                       :aws-subnet-cidr :aws-root-volume-size-gb
                       :aws-ssh-authorized-keys]
           :secrets []
           :tofu-env {}}
    "google" {:required [:google-project :google-region :google-zone
                          :google-name :google-machine-type
                          :google-image-project :google-image-family
                          :google-image-id :google-subnet-cidr :google-boot-disk-size-gb
                          :google-ssh-authorized-keys]
              :secrets []
              :tofu-env {}}
    "digitalocean" {:required [:digitalocean-name :digitalocean-region
                               :digitalocean-size :digitalocean-image
                               :digitalocean-ssh-keys]
                    :secrets [:do-token]
                    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
    "hcloud" {:required [:hcloud-name :hcloud-image :hcloud-server-type
                         :hcloud-location :hcloud-ssh-keys]
              :secrets [:hcloud-token]
              :tofu-env {:hcloud-token "HCLOUD_TOKEN"}}
    "yandex" {:required [:yandex-cloud-id :yandex-folder-id :yandex-zone
                         :yandex-image-family :yandex-name :yandex-subnet-cidr
                         :yandex-platform-id :yandex-cores :yandex-memory-gb
                         :yandex-core-fraction :yandex-disk-size-gb
                         :compute-pubkey]
              :secrets [:yandex-token]
              :tofu-env {:yandex-token "YC_TOKEN"}}
    ;; OCI authenticates from ~/.oci/config, selected by :oci-config-file-profile,
    ;; so it needs no credential of its own here.
    "oci" {:required [:oci-config-file-profile :oci-subnet-id :oci-compartment-id
                      :oci-availability-domain :oci-display-name :oci-shape
                      :oci-ocpus :oci-memory-in-gbs :oci-boot-volume-size-in-gbs
                      :oci-boot-volume-vpus-per-gb :oci-ssh-authorized-keys]
           :secrets []
           :tofu-env {}}
    "no-infra" {:required [:no-infra-compute-ip :no-infra-compute-user
                           :no-infra-compute-sudoer :no-infra-compute-uid]
                :secrets []
                :tofu-env {}}}

   :provider-smtp
   ;; Resend needs no non-secret keys: its relay is identical for every
   ;; account and is hard-coded in tools/resend-smtp. The password is not in
   ;; :tofu-env because tofu never sends mail — Ansible looks it up at play time.
   {"resend" {:required []
              :secrets [:resend-api-key :resend-password]
              :tofu-env {:resend-api-key "RESEND_API_KEY"}}
    "no-infra" {:required [:no-infra-smtp-server :no-infra-smtp-port
                           :no-infra-smtp-username]
                :secrets [:no-infra-smtp-password]
                :tofu-env {}}}

   :provider-dns
   {"cloudflare" {:required []
                  :secrets [:cloudflare-api-token]
                  :tofu-env {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}}
    ;; Unlike Cloudflare, the Yandex DNS stage creates the public zones itself,
    ;; so it needs the folder to put them in. The token is the same one the
    ;; Yandex compute provider uses; selecting both demands it once.
    "yandex" {:required [:yandex-cloud-id :yandex-folder-id]
              :secrets [:yandex-token]
              :tofu-env {:yandex-token "YC_TOKEN"}}
    "no-infra" {:required [] :secrets [] :tofu-env {}}}

   :provider-backend
   {"local" {:required [] :secrets [] :tofu-env {}}
    "s3" {:required [:s3-bucket :s3-region] :secrets [] :tofu-env {}}
    ;; R2 is an S3-compatible backend, so it authenticates through the AWS chain.
    "r2" {:required [:r2-bucket :r2-endpoint]
          :secrets [:r2-access-key-id :r2-secret-access-key]
          :tofu-env {:r2-access-key-id "AWS_ACCESS_KEY_ID"
                     :r2-secret-access-key "AWS_SECRET_ACCESS_KEY"}}}})

(def ^:private slots
  [:provider-compute :provider-smtp :provider-dns :provider-backend])

(defn- entry
  [opts slot]
  (get-in providers [slot (get opts slot)]))

(defn tofu-env
  "Flat key -> the environment variable OpenTofu reads it from, for the
  provider selected in `slot`."
  [opts slot]
  (:tofu-env (entry opts slot) {}))

(defn- slot-keys
  [opts field]
  (mapcat #(get (entry opts %) field []) slots))

(defn placeholder?
  "Whether a value is missing in the ways a hand-edited EDN file produces:
  absent, blank, or still carrying the scaffold's REPLACE_ME."
  [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x)
               (= "REPLACE_ME" (str/upper-case x))))))

(defn- missing-keys
  [opts ks]
  (keep (fn [k] (when (placeholder? (get opts k)) k)) ks))

(def ^:private domain-re
  #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def ^:private env-name-re #"^[A-Z_][A-Z0-9_]*$")
(def ^:private repo-re #"^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

(defn- app-errors
  [applications]
  (mapcat
   (fn [[idx {:keys [host image env github]}]]
     (concat
      (when (or (placeholder? host) (not (re-matches domain-re (str host))))
        [(format ":once :applications[%d] has an invalid :host" idx)])
      (when (placeholder? image)
        [(format ":once :applications[%d] requires :image" idx)])
      ;; :github is optional; a value that is present has to name a repository,
      ;; because it is interpolated straight into a gh invocation.
      (when-not (or (nil? github) (re-matches repo-re (str github)))
        [(format ":once :applications[%d] :github must be owner/repo" idx)])
      (when-not (or (nil? env) (map? env) (sequential? env))
        [(format ":once :applications[%d] :env must map container variable names to colors.yml keys"
                 idx)])
      (when (map? env)
        (mapcat (fn [[var-name k]]
                  (concat
                   (when-not (re-matches env-name-re (str (name var-name)))
                     [(format ":once :applications[%d] has an invalid container variable name %s"
                              idx var-name)])
                   (when (placeholder? k)
                     [(format ":once :applications[%d] :env %s needs a colors.yml key"
                              idx var-name)])))
                env))))
   (map-indexed vector applications)))

(defn- app-secret-keys
  "Flat keys referenced by application :env maps."
  [applications]
  (->> applications
       (mapcat (fn [{:keys [env]}] (when (map? env) (vals env))))
       (remove placeholder?)
       (map keyword)))

(defn state-errors
  "Everything wrong with `opts` that does not depend on credentials, as a
  vector of messages. Empty means the desired state is renderable."
  [opts]
  (let [applications (get-in opts [:once :applications])]
    (vec
     (concat
      (map #(str % " is required")
           (missing-keys opts (concat [:profile :workdir]
                                      (slot-keys opts :required))))
      (for [slot slots
            :let [provider (get opts slot)]
            :when (not (contains? (get providers slot) provider))]
        (str "unsupported " slot " " (pr-str provider)))
      (when-not (and (sequential? applications) (seq applications))
        [":once :applications must be a non-empty sequence"])
      (when (sequential? applications)
        (app-errors applications))
      (when-not (boolean? (:compute-prevent-destroy opts))
        [":compute-prevent-destroy must be true or false"])
      ;; Yandex requires :compute-pubkey; for other providers it is optional.
      ;; Either way, a value that is present must look like a public key.
      (when-not (or (nil? (:compute-pubkey opts))
                    (placeholder? (:compute-pubkey opts))
                    (str/starts-with? (str (:compute-pubkey opts)) "ssh-"))
        [":compute-pubkey must be an SSH public key"])))))

(defn deploy-groups
  "One entry per distinct GitHub repository named in desired state, carrying
  every host that repository serves: `{:github \"owner/repo\" :hosts [...]}`.

  The repository, not the application, is the unit a deploy key belongs to. It
  is where the key is stored — a GitHub environment — and what triggers its
  use. Grouping here is what lets one image answer for several hosts: those
  hosts are one repository, one pipeline, one push. Keyed per application
  instead, two applications naming the same repository publish into the same
  environment and the second silently overwrites the first's key.

  Applications without `:github` produce no group, and so no key at all.

  Order is first appearance in `:applications`, and hosts within a group keep
  their desired-state order, so the rendered artifact stays a pure function of
  the file all three colours read."
  [opts]
  (let [apps (remove #(placeholder? (:github %))
                     (get-in opts [:once :applications]))
        by-repo (group-by #(str (:github %)) apps)]
    (mapv (fn [repo] {:github repo :hosts (mapv #(str (:host %)) (by-repo repo))})
          (distinct (map #(str (:github %)) apps)))))

(defn secret-errors
  "Credentials the selected providers need that no `COLORS_PAR_*` variable
  supplied. Application `:env` keys join the list only on create, since
  deleting does not need to reach the applications. A GitHub token is needed
  for both, because delete has to revoke what create published."
  [opts]
  (let [applications (get-in opts [:once :applications])
        ks (cond-> (slot-keys opts :secrets)
             (seq (deploy-groups opts))
             (conj :github-token)

             (= :create (:green/event opts))
             (concat (app-secret-keys applications)))]
    (map #(str "required credential is not set: " (green-cli/par-name %))
         (distinct (missing-keys opts ks)))))
