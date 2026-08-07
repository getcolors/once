(ns io.github.getcolors.once.tools
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.ansible :as ansible]
   [green.scaffold :as sc]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [green.yaml :as yaml]
   [io.github.getcolors.once.github :as github]
   [io.github.getcolors.once.utils :as utils]
   [io.github.getcolors.once.validate :as validate]))

(def ^:private template-root "io.github.getcolors.once.tools")
(def ^:private raw-template :io.github.getcolors.once/raw)
(def ^:private template-opts {:tag-open \<
                              :tag-close \>
                              :filter-open \{
                              :filter-close \}})

(defn tool-dir
  "Return the isolated working directory for `tool` in the active profile.

  A relative workdir is resolved against the directory holding colors.yml, not
  the current one, so every colour shares one work directory however deep in
  the project it was invoked from."
  [opts tool]
  (let [workdir (io/file (or (:workdir opts) ".colors"))
        state-dir (when-not (.isAbsolute workdir)
                    (some-> (:green/state-file opts) io/file .getAbsoluteFile .getParent))
        root (if state-dir (io/file state-dir workdir) workdir)]
    (str (io/file root (or (:profile opts) "default") tool))))

(defn- tool-template
  [tool provider file]
  (keyword (str template-root "." tool "." provider) file))

(defn- static-template
  [tool file]
  (keyword (str template-root "." tool) file))

(defn- template-spec
  [template target data]
  {:template template
   :target target
   :data data
   :opts template-opts})

(defn- raw-spec
  [target content]
  (template-spec raw-template target {:content content}))

(defn- output-params
  [opts]
  (some-> (get-in opts [:tofu/outputs :params]) walk/keywordize-keys))

(defn- credential-env
  "Environment additions for the providers selected in `slots`, plus whatever
  the state backend needs — every stage reads and writes state, so the backend
  credentials belong to all of them. Unset credentials are omitted, so build
  and dry-run stay credential-free."
  [opts & slots]
  (not-empty
   (into {}
         (keep (fn [[k env-var]]
                 (when-let [v (not-empty (str (get opts k)))]
                   [env-var v])))
         (apply merge (map #(validate/tofu-env opts %)
                           (conj (vec slots) :provider-backend))))))

(defn backend-credential-env
  "Environment additions for a process that only reads OpenTofu state, such as
  `tofu output`. Provider credentials are left out on purpose: reading state
  never calls a provider API."
  [opts]
  (credential-env opts))

(defn- tofu-with-spec
  "green.tofu/tofu-with-spec plus this project's params adoption: on a real
  apply the stage's `params` output becomes `result-key`, and `fallback`
  stands in for the values a build or dry-run cannot know."
  [opts dir specs fallback result-key env]
  (let [result (tofu/tofu-with-spec opts specs {:dir dir :env env})]
    (cond
      (or (nil? result-key) (wf/failed? result)) result
      (= :build (:green/event opts)) (assoc result result-key fallback)
      (= :delete (:green/event opts)) result
      :else (assoc result result-key
                   (merge fallback (or (output-params result) {}))))))

(defn- fallback-compute-params
  [{:keys [profile provider-compute] :as opts}]
  (let [name (or profile "once")]
    (case provider-compute
      "azure" {:ip "192.168.0.1"
               :sudoer "ubuntu"
               :uid "1000"
               :name name
               :user "ubuntu"}
      "aws" {:ip "192.168.0.1"
             :sudoer "ubuntu"
             :uid "1000"
             :name name
             :user "ubuntu"}
      "oci" {:ip "192.168.0.1"
             :sudoer "ubuntu"
             :uid "1001"
             :name name
             :user "ubuntu"}
      "yandex" {:ip "192.168.0.1"
                :sudoer "ubuntu"
                :uid "1000"
                :name name
                :user "ubuntu"}
      "google" {:ip "192.168.0.1"
                :sudoer "ubuntu"
                :uid "1000"
                :name name
                :user "ubuntu"}
      "no-infra" (cond-> {:ip (or (:no-infra-compute-ip opts) "192.168.0.1")
                          :sudoer (or (:no-infra-compute-sudoer opts) "root")
                          :name name
                          :user (or (:no-infra-compute-user opts) "root")}
                   (:no-infra-compute-uid opts) (assoc :uid (:no-infra-compute-uid opts)))
      {:ip "192.168.0.1"
       :sudoer "root"
       :name name
       :user "root"})))

(def ^:private resend-smtp
  "Resend's relay is the same for every account, so it is not desired state.
  Only the password is, and it arrives as COLORS_PAR_RESEND_PASSWORD."
  {:smtp_server "smtp.resend.com"
   :smtp_port 587
   :smtp_username "resend"})

(defn- fallback-smtp-params
  [{:keys [provider-smtp] :as opts}]
  (merge {:domains []}
         (case provider-smtp
           "no-infra" {:smtp_username (:no-infra-smtp-username opts)
                       :smtp_password (:no-infra-smtp-password opts)
                       :smtp_server (:no-infra-smtp-server opts)
                       :smtp_port (:no-infra-smtp-port opts)}
           "resend" (assoc resend-smtp
                           :smtp_password (:resend-password opts)
                           :domains (mapv (fn [zone]
                                            {:zone zone
                                             :id (str "domain-id-not-defined-" zone)
                                             :records []})
                                          (utils/apps-domains opts)))
           {})))

(defn tofu-compute-step
  [opts]
  (let [provider (or (:provider-compute opts) "hcloud")
        dir (tool-dir opts "tofu-compute")
        specs [(template-spec (tool-template "tofu" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-compute-params opts) :once/compute-params
                    (credential-env opts :provider-compute))))

(defn- with-zones
  "Templates receive the sorted DNS zones derived from the application hosts;
  no domain key is carried in desired state."
  [opts]
  (let [zones (utils/apps-domains opts)]
    (assoc opts
           :zones zones
           :zones-hcl (tofu/hcl-list zones))))

(defn tofu-smtp-step
  [opts]
  (let [opts (with-zones opts)
        provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp")
        specs [(template-spec (tool-template "tofu-smtp" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs (fallback-smtp-params opts) :once/smtp-params
                    (credential-env opts :provider-smtp))))

(defn- add-fqn-suffix
  [fqn suffix]
  (if-let [ns (namespace fqn)]
    (keyword ns (str (name fqn) suffix))
    (keyword (str (name fqn) suffix))))

(defn- cloudflare-zone-id
  [zone]
  (format "${data.cloudflare_zone.domains[%s].id}" (pr-str zone)))

(defn- yandex-zone-id
  [zone]
  (format "${yandex_dns_zone.domains[%s].id}" (pr-str zone)))

(defn- yandex-fqdn
  "Yandex record names and targets are absolute; without the trailing dot the
  API would read them as relative to the zone."
  [s]
  (cond-> (str s)
    (not (str/ends-with? (str s) ".")) (str ".")))

(def ^:private dns-record-resources
  "DNS provider -> the record resource its generated .tf.json files declare.
  A provider absent here (no-infra) gets no generated records at all."
  {"cloudflare" :cloudflare_dns_record
   "yandex" :yandex_dns_recordset})

(defn- app-record
  [provider ip host]
  (let [zone (utils/registrable-domain host)]
    (case provider
      "cloudflare" {:zone_id (cloudflare-zone-id zone)
                    :name host
                    :content ip
                    :type "A"
                    :proxied true
                    :ttl 1}
      ;; Yandex has no proxy: the record resolves straight to the server.
      "yandex" {:zone_id (yandex-zone-id zone)
                :name (yandex-fqdn host)
                :type "A"
                :ttl 300
                :data [ip]})))

(defn- smtp-record
  [provider zone {:keys [name priority type value]}]
  (case provider
    "cloudflare" (cond-> {:zone_id (cloudflare-zone-id zone)
                          :name name
                          :ttl "1"
                          :type type
                          :proxied false}
                   (= type "TXT") (merge {:content (format "\"%s\"" value)})
                   (= type "MX") (merge {:priority priority
                                         :content value}))
    ;; Yandex recordsets carry everything in :data — the MX priority is part
    ;; of the value, and TXT values are quoted like a zone file.
    "yandex" {:zone_id (yandex-zone-id zone)
              :name (yandex-fqdn name)
              :ttl 300
              :type type
              :data [(case type
                       "TXT" (format "\"%s\"" value)
                       "MX" (format "%s %s" priority (yandex-fqdn value))
                       value)]}))

(defn render-fn
  [src {:keys [provider domains applications ip]}]
  (let [provider (or provider "cloudflare")
        resource (dns-record-resources provider)]
    (case src
      ;; One A record per application host — proxied on Cloudflare, plain on
      ;; Yandex. There is no implicit apex or wildcard record: only the hosts
      ;; desired state names resolve to the server.
      :apps (tofu/constructs-json
             (for [{:keys [host]} applications]
               (tofu/construct :resource
                               resource
                               (add-fqn-suffix ::app-dns (str "-" host))
                               (app-record provider ip host))))
      :smtp (tofu/constructs-json
             (for [{:keys [zone records]} domains
                   {:keys [record type] :as r} records]
               (tofu/construct :resource
                               resource
                               (add-fqn-suffix ::smtp-dns
                                               (format "-%s-%s-%s" zone record type))
                               (smtp-record provider zone r)))))))

(defn- joined-params
  [opts]
  (let [branches (:green/branches opts)
        compute (or (some :once/compute-params branches)
                    (:once/compute-params opts)
                    (fallback-compute-params opts))
        smtp (or (some :once/smtp-params branches)
                 (:once/smtp-params opts)
                 (fallback-smtp-params opts))]
    (-> opts
        (merge compute smtp)
        (assoc :once/compute-params compute
               :once/smtp-params smtp))))

(defn tofu-dns-step
  [opts]
  (let [opts (with-zones (if (= :delete (:green/event opts)) opts (joined-params opts)))
        provider (or (:provider-dns opts) "cloudflare")
        dir (tool-dir opts "tofu-dns")
        specs (cond-> [(template-spec (tool-template "tofu-dns" provider "main.tf")
                                      (str dir "/main.tf")
                                      opts)]
                (contains? dns-record-resources provider)
                (conj (raw-spec (str dir "/apps.tf.json")
                                (render-fn :apps {:provider provider
                                                  :applications (get-in opts [:once :applications])
                                                  :ip (:ip opts)}))
                      (raw-spec (str dir "/smtp.tf.json")
                                (render-fn :smtp {:provider provider
                                                  :domains (:domains opts)}))))]
    (tofu-with-spec opts dir specs {} nil
                    (credential-env opts :provider-dns))))

(defn tofu-smtp-post-step
  [opts]
  (let [domain-ids (into (sorted-map)
                         (map (juxt :zone :id))
                         (:domains opts))
        opts (assoc opts :domain-ids-hcl (tofu/hcl-map domain-ids))
        provider (or (:provider-smtp opts) "resend")
        dir (tool-dir opts "tofu-smtp-post")
        specs [(template-spec (tool-template "tofu-smtp-post" provider "main.tf")
                              (str dir "/main.tf")
                              opts)]]
    (tofu-with-spec opts dir specs {} nil
                    (credential-env opts :provider-smtp))))

(defn data-fn
  ([data] (data-fn data nil))
  ([{:keys [ip sudoer] :as data} _]
   (let [sudoer (or sudoer "root")]
     (merge data {:sudoer sudoer
                  :hosts [(or ip "64.227.72.100")]
                  :users []}))))

(defn inventory
  [{:keys [sudoer hosts users]}]
  (let [users (->> users
                   (filter (complement :remove))
                   (mapcat (fn [user]
                             (map #(assoc user :host %) hosts))))
        admins (mapcat (fn [admin]
                         (map #(assoc admin :host % :name sudoer) hosts))
                       [{:ansible_user sudoer}])
        users-hosts (reduce (fn [result {:keys [name uid host]}]
                              (assoc result (format "%s@%s" name host)
                                     {:ansible_host host
                                      :ansible_user name
                                      :uid uid}))
                            {}
                            users)
        admins-hosts (reduce (fn [result {:keys [name host]}]
                               (assoc result (format "root@%s" host)
                                      {:ansible_host host
                                       :ansible_user name}))
                             {}
                             admins)
        result {:all {:children {:admin {:hosts admins-hosts}
                                 :users {:hosts users-hosts}}}}]
    (json/generate-string result {:pretty true})))

(defn- par-lookup
  "Jinja expression resolving a secret under the one parameter namespace every
  colour shares. The expression is identical in all three packages, so their
  rendered artifacts remain byte-compatible."
  [k]
  (let [suffix (-> (name k) (str/replace "-" "_") str/upper-case)]
    (format "{{ lookup('env','COLORS_PAR_%s') }}" suffix)))

(defn- resolve-env
  "Resolve an application `:env` map of container variable name -> flat opts key
  into the [\"KEY=VALUE\"] list the once module expects. Each value defers to
  the key's `COLORS_PAR_*` variable, looked up when Ansible runs, so application
  secrets reach the host without being written into the rendered file. An unset
  variable still resolves to an empty value rather than the string \"null\".
  A list is passed through untouched."
  [env]
  (if (map? env)
    (mapv (fn [[var-name k]]
            (str (name var-name) "=" (par-lookup (keyword k))))
          env)
    env))

(defn- application-data
  [smtp app]
  (let [zone (utils/registrable-domain (:host app))
        smtp (assoc smtp :smtp_from (format "Info <info@notifications.%s>" zone))]
    ;; :github never reaches the host. It says where the deploy credentials are
    ;; published, which is no business of the module reconciling containers, and
    ;; a ninth key here also tips a Clojure map out of insertion order and away
    ;; from the byte parity the other colours hold to.
    (cond-> (merge (dissoc app :github) smtp)
      (map? (:env app)) (assoc :env (resolve-env (:env app))))))

(def ^:private smtp-password-keys
  {"resend" :resend-password
   "no-infra" :no-infra-smtp-password})

(defn ansible-once
  [{:keys [once provider-smtp] :as opts}]
  (let [pw-key (smtp-password-keys (or provider-smtp "resend"))
        smtp (select-keys opts [:smtp_server :smtp_port :smtp_username :smtp_password])
        smtp (cond-> smtp
               (and pw-key (:smtp_password smtp))
               (assoc :smtp_password (par-lookup pw-key)))
        once (update once :applications
                     (fn [applications]
                       (mapv #(application-data smtp %) applications)))
        data [{:name "Reconcile ONCE applications"
               :become true
               :once once}]]
    (yaml/generate-string data)))

(defn render
  [target data]
  (case target
    :inventory (inventory data)
    :ansible-once (ansible-once data)))

(defn deploy-keys-content
  "The authorized_keys lines for the current generation, one per repository
  named in desired state.

  Each key carries every host its repository serves inside the ForceCommand, so
  a key leaked from one repository cannot redeploy another repository's
  application, and the client never has to name a host at all. Pure and
  deterministic: this is rendered into the artifact the colours compare byte
  for byte, which is also why the key comment holds no timestamp."
  [opts]
  (let [lines (map (fn [{:keys [hosts public]}]
                     (format "restrict,command=\"/usr/local/bin/deploy %s\" %s"
                             (str/join " " hosts) public))
                   (github/public-keys opts))]
    (if (seq lines) (str (str/join "\n" lines) "\n") "")))

(defn- ansible-remote-specs
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        data (data-fn opts)]
    [(template-spec (static-template "ansible" "ansible.cfg")
                    (str dir "/ansible.cfg")
                    data)
     (template-spec (static-template "ansible" "files/authorized-keys")
                    (str dir "/files/authorized-keys")
                    data)
     (raw-spec (str dir "/deploy_keys") (deploy-keys-content opts))
     (template-spec (static-template "ansible" "main.yml")
                    (str dir "/main.yml")
                    data)
     (template-spec (static-template "ansible" "files/deploy")
                    (str dir "/files/deploy")
                    data)
     (template-spec (static-template "ansible" "library/once")
                    (str dir "/library/once")
                    data)
     (raw-spec (str dir "/inventory.json") (inventory data))
     (raw-spec (str dir "/once.yml") (ansible-once data))]))

(defn ansible-remote-step
  [opts]
  (let [dir (tool-dir opts "ansible-remote")
        rendered (sc/scaffold opts (ansible-remote-specs opts))]
    (if (or (= :build (:green/event opts))
            (= :delete (:green/event opts)))
      rendered
      (ansible/ansible-step rendered {:dir dir
                                      :inventory "inventory.json"
                                      :playbooks {:create "main.yml"}
                                      :host-key-checking false}))))

(defn- local-host-alias
  "The SSH alias the local playbook manages. Tofu reports it as `name`, itself
  rendered from `profile`, so `profile` answers when state cannot be read."
  [data]
  (or (not-empty (str (:name data)))
      (not-empty (str (:profile data)))
      "once"))

(defn ansible-local-step
  [opts]
  (let [dir (tool-dir opts "ansible-local")
        data (data-fn opts)
        specs [(template-spec (static-template "ansible-local" "ansible.cfg")
                              (str dir "/ansible.cfg")
                              data)
               (template-spec (static-template "ansible-local" "inventory.ini")
                              (str dir "/inventory.ini")
                              data)
               (template-spec (static-template "ansible-local" "main.yml")
                              (str dir "/main.yml")
                              data)]
        delete? (= :delete (:green/event opts))
        ;; The playbook's variables are Ansible's, not Selmer's, so they arrive
        ;; as extra-vars: the local inventory targets localhost only and carries
        ;; no host vars of its own. `name` is reserved in Ansible, hence
        ;; host_alias. block_state drives blockinfile in both directions.
        config {:dir dir
                :inventory "inventory.ini"
                :playbooks {:create "main.yml" :delete "main.yml"}
                :extra-vars {:host_alias (local-host-alias data)
                             :ip (:ip data)
                             :user (:user data)
                             :block_state (if delete? "absent" "present")}}]
    ;; Delete renders the playbook so it can run, removes the managed block
    ;; from ~/.ssh/config, and only then deletes the rendered tree.
    (ansible/ansible-with-spec opts config specs)))
