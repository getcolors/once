;; Print how green's YAML reader typed every scalar in the parity corpus, one
;; `key=type:value` line per entry. Red and blue print the same shape, so
;; parity.sh can diff them directly.
(require '[clojure.string :as str]
         '[green.cli :as green-cli])

(defn- describe
  [v]
  (cond
    (nil? v) "null:"
    (boolean? v) (str "bool:" v)
    (integer? v) (str "int:" v)
    (and (number? v) (Double/isFinite (double v)) (== v (long v))) (str "int:" (long v))
    (number? v) (str "float:" v)
    (string? v) (str "string:" v)
    :else (str "other:" (pr-str v))))

(let [path (first *command-line-args*)
      state (green-cli/read-state path (slurp path))]
  (doseq [k (sort (map name (keys state)))]
    (println (str k "=" (describe (get state (keyword k)))))))
