(require '[cheshire.core :as json] '[io.github.getcolors.once.workflow :as workflow])
(println (json/generate-string (mapv (fn [event] (into [(name event)] (map (fn [step] (mapv #(subs (str %) 1) (rest (workflow/wire-fn step {:green/event event})))) [:once/tofu-smtp-post :once/ansible-local :once/ansible-remote]))) [:create :build])))
