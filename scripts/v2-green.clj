(require '[cheshire.core :as json] '[clojure.walk :as walk]
         '[io.github.getcolors.once.access :as access]
         '[io.github.getcolors.once.machine :as machine]
         '[io.github.getcolors.once.tools :as tools]
         '[io.github.getcolors.once.workflow :as workflow]
         '[io.github.getcolors.compute-node :as node]
         '[io.github.getcolors.once.github :as github]
         '[clojure.java.io :as io])
(let [{:keys [base cases access secrets graphs registration]} (json/parse-string (slurp (first *command-line-args*)) true)
      operations (atom [])
      _ (doseq [{:keys [event guard]} registration]
          (with-redefs [node/compute-registration! (fn [_ _ operation] (swap! operations conj operation) {:status "ready"})]
            (access/registration-step (assoc base :provider-compute "digitalocean" :green/event (keyword event) :compute-require-existing-state guard :once/ssh-resource machine/placeholder-resource))))
      key-path (atom nil)
      key-alive (atom false)
      _ (try (access/scoped (fn []
                  (let [[keys error] (github/generate-keys {:profile "parity" :once {:applications [{:host "www.example.com" :image "example" :github "example/site"}]}})]
                    (when error (throw (ex-info error {})))
                    (reset! key-path (:private-file (first keys)))
                    (reset! key-alive (.exists (io/file @key-path)))
                    (throw (ex-info "fixture stage failure" {})))))
             (catch Exception e (when-not (= "fixture stage failure" (.getMessage e)) (throw e))))
      result {:key_cleanup [@key-alive (not (.exists (io/file @key-path)))] :registration_operations @operations :errors (mapv (fn [{:keys [name opts]}] [name (machine/errors (merge base opts))]) cases)
              :graphs (mapv (fn [{:keys [event steps]}] [event (mapv (fn [step] [step (mapv #(subs (str %) 1) (rest (workflow/wire-fn (keyword "once" step) {:green/event (keyword event)})))]) steps)]) graphs)
              :identity_args (mapv access/identity-args access)
              :ssh_args (mapv access/ssh-args access)
              :secret_env (tools/ansible-secret-env secrets)}]
  (println (json/generate-string (walk/postwalk #(if (map? %) (into (sorted-map) %) %) result))))
