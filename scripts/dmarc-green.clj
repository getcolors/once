;; Compare only DMARC errors: unrelated provider diagnostics have older formatting differences.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[io.github.getcolors.once.validate :as validate])
(let [{:keys [base cases]} (json/parse-string (slurp (first *command-line-args*)) keyword)]
  (doseq [{:keys [name opts]} cases]
    (println (json/generate-string
              [name (filterv #(str/starts-with? % "smtp-dmarc-")
                             (validate/state-errors (merge base opts)))]))))
