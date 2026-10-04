(require '[cheshire.core :as json] '[clojure.walk :as walk]
         '[io.github.getcolors.once.access :as access]
         '[io.github.getcolors.once.workflow :as workflow]
         '[green.ansible :as ansible]
         '[io.github.getcolors.once.tools :as tools]
         '[io.github.getcolors.once.validate :as validate]
         '[io.github.getcolors.once.machine :as machine])
(let [{:keys [base cases]} (json/parse-string (slurp (first *command-line-args*)) true)
      rows (atom [])]
  (with-redefs [access/lock! (constantly nil) access/install-lock! (constantly nil)]
    (doseq [c cases]
      (let [calls (atom [])
            export (fn [_ op] (swap! calls conj op)
                     (if (= (:export_failure c) op) {:status "error" :error {:message "fixture export refusal"}}
                       {:status (if (:absent c) "absent" (if (= op "remove") "removed" "installed")) :private_key_file "/fixture/encrypted"}))
            config (fn [payload] (let [phase (if (:check_only payload) "preflight" (:block_state payload))]
                                  (swap! calls conj phase) {:exit (if (= (:config_failure c) phase) 7 0) :err "fixture config refusal"}))
            opts (assoc base :green/event (keyword (str "ssh-" (:command c))) :green/dry-run (boolean (:dry_run c)))
            result ((if (= (:command c) "install") access/install-step access/uninstall-step) opts export config)]
        (swap! rows conj [(:name c) @calls (:green/exit result) (:green/err result)]))))
  (with-redefs [access/install-lock! (constantly nil)]
    (doseq [status ["installed" "absent" "error"]]
      (let [calls (atom [])
            value (with-redefs [access/export-operation (fn [_ op] (swap! calls conj op) {:status status :private_key_file "/fixture/encrypted" :error {:message "fixture ownership refusal"}})]
                    (try (access/installed-identity (assoc base :green/event :create)) (catch Exception e (.getMessage e))))]
        (swap! rows conj [(str "preserve-" status) @calls value])))
    (doseq [[event dry] [[:build false] [:create true]]]
      (let [calls (atom [])
            value (with-redefs [access/export-operation (fn [_ op] (swap! calls conj op) (throw (Exception. "unexpected export")))]
                    (access/installed-identity (assoc base :green/event event :green/dry-run dry)))]
        (swap! rows conj [(str "preserve-" (name event) (when dry "-dry")) @calls value]))))
  (doseq [[command dry] [[:ssh-install false] [:ssh-uninstall false] [:ssh-install true] [:ssh-uninstall true]]]
    (let [calls (atom [])
          step (fn [label] (fn [opts & _] (when-not (machine/planning? opts) (swap! calls conj label)) opts))
          result (with-redefs [validate/state-errors (constantly []) access/lock! (constantly nil)
                              access/resource-step (step "resource-inspect")
                              access/registration-step (step "registration-inspect")
                              machine/load-inventory (step "live-connection")
                              access/agent-step (fn [opts] (swap! calls conj "agent") opts)]
                   (workflow/start-step (assoc base :green/event command :green/dry-run dry) {}))]
      (swap! rows conj [(str "start-" (name command) (when dry "-dry")) @calls (or (:green/exit result) 0)])))
  (doseq [command [:ssh-install :ssh-uninstall]]
    (swap! rows conj [(str "graph-" (name command))
                     (mapv (fn [step] (mapv #(subs (str %) 1) (rest (workflow/wire-fn step {:green/event command}))))
                           [:once/start (keyword "once" (name command))])]))
  (doseq [[event identity] [[:create "/fixture/encrypted"] [:create nil] [:delete "/fixture/encrypted"]]]
    (let [calls (atom [])
          result (with-redefs [access/installed-identity (fn [_] (swap! calls conj "inspect") identity)
                              access/install-lock! (constantly nil)
                              ansible/ansible-with-spec (fn [_ config _] (:extra-vars config))]
                   (tools/ansible-local-step (assoc base :workdir "/fixture/workdir" :ssh-private-key-path "/fixture/public.pub" :green/event event)))]
      (swap! rows conj [(str "local-" (name event) (if identity "-installed" "-absent")) @calls result])))
  (swap! rows conj ["payload" (access/config-payload base "present" "/fixture/encrypted") (access/config-payload base "absent" nil)])
  (println (json/generate-string (walk/postwalk #(if (map? %) (into (sorted-map) %) %) @rows))))
