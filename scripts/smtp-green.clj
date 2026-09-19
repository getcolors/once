(require '[cheshire.core :as json]
         '[io.github.getcolors.once.tools :as tools])
(println (tools/render-fn :smtp (json/parse-string (slurp (first *command-line-args*)) keyword)))
