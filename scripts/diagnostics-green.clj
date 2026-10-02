(require '[cheshire.core :as json] '[io.github.getcolors.once.machine :as machine])
(doseq [{:keys [name event result]} (json/parse-string (slurp (first *command-line-args*)) true)]
  (let [opts (machine/failed-result {:green/event (keyword event)} result)]
    (println (json/generate-string [name (:green/exit opts) (:green/err opts)]))))
