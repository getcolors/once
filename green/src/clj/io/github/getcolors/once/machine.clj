(ns io.github.getcolors.once.machine
  "ONCE's single host requirement and application parameter adapter."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [green.cli :as cli]
            [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-deployment-request :as request]
            [io.github.getcolors.compute-inspection :as inspection]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-ssh :as ssh]))
(def topology [{:role nil :count 1}])
(defn requirements [opts]
  {:single_host true :private false
   :security {:egress "all" :private_filter false
              :ingress (mapv (fn [[id port]]
                               (let [suffix (if (= id "ssh") "ssh-sources" "http-sources")
                                     sources (request/source-cidrs opts suffix (str "compute-" suffix))]
                                 (when-not (seq sources) (throw (ex-info (str "compute-" suffix " is required") {})))
                                 {:id id :protocol "tcp" :from_port port :to_port port :sources sources}))
                             [["ssh" 22] ["http" 80] ["https" 443]])}
   :legacy_state_keys [(str (:profile opts) "/tofu-compute.tfstate")]})
(defn errors [opts]
  (let [errors (compute/validate opts)]
    (if (seq errors) errors
        (try (planning/plan-deployment opts topology (requirements opts)) []
             (catch Exception error [(.getMessage error)])))))
(defn params [opts result]
  (let [node (first (get-in result [:cluster :nodes]))
        path (or (get-in result [:key :private_key_path]) (:ssh_identity_file node))
        path (if (and path (= "planned" (:status result)))
               (str/replace path "$HOME/.ssh" "/home/build-placeholder/.ssh") path)]
    (cond-> (assoc node :ssh-keygen (= "managed" (:mode (ssh/mode opts))))
      path (assoc :ssh-private-key-path path))))
(defn fallback-params [opts]
  (when-not (or (= :build (:green/event opts)) (:green/dry-run opts))
    (throw (ex-info "compute inventory unavailable" {})))
  (params opts (planning/plan-deployment opts topology (requirements opts))))
(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by (comp name key) value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))
(defn step [opts]
  (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts))
        result (if planning? (planning/plan-deployment opts topology (requirements opts))
                   (orchestration/orchestrate opts topology (requirements opts)))]
    (when planning?
      (doseq [[stage documents] (cons ["shared" (get-in result [:documents :shared])]
                                      (map (fn [[id documents]] [(str "nodes/" id) documents]) (get-in result [:documents :nodes])))
              :let [state-key (if (= stage "shared") (get-in result [:state_keys :shared]) (get-in result [:state_keys :nodes (last (str/split stage #"/"))]))]
              [filename document] (assoc documents "backend.tf.json" (:config (compute/backend-plan opts state-key)))]
        (let [target (io/file (cli/stage-dir opts "tofu-compute") stage filename)]
          (io/make-parents target) (spit target (str (compute-json document 0) "\n")))))
    (cond
      (not (contains? #{"planned" "ready" "destroyed"} (:status result)))
      (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused"))
      (:cluster result) (let [adopted (params opts result)]
                          (assoc (merge opts adopted) :once/compute-params adopted :colors-compute/cluster (:cluster result) :green/exit 0))
      :else (assoc opts :green/exit 0))))
(defn load-inventory
  ([opts] (load-inventory opts (into {} (System/getenv))))
  ([opts env]
   (let [result (inspection/read-deployment opts env)]
     (cond
       (and (= "destroyed" (:status result)) (= :delete (:green/event opts)))
       (assoc opts :green/exit 0 :colors-compute/already-destroyed true)
       (= "present" (:status result))
       (let [adopted (params opts result)]
         (assoc (merge opts adopted) :once/compute-params adopted :colors-compute/cluster (:cluster result) :green/exit 0))
       :else (assoc opts :green/exit 1 :green/err "compute inventory unavailable; legacy state requires explicit migration")))))
