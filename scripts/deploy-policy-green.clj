(require '[cheshire.core :as json] '[clojure.string :as str]
         '[io.github.getcolors.once.validate :as validate])
(doseq [app (json/parse-string (slurp (first *command-line-args*)) true)]
  (println (json/generate-string
            (filterv #(or (str/starts-with? % "deploy-") (str/starts-with? % "stop-first") (str/starts-with? % "application smtp"))
                     (validate/state-errors {:once {:applications [(merge {:host "wiki.example.com" :image "example/wiki"} app)]}})))))
