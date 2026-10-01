(ns pin
  "Stamp every bundled launcher with the commits a standalone copy should
  resolve.

  This is a maintainer command for this repository, not something a user of a
  skill ever runs: it reads the HEAD of the checkout it is invoked in, so
  anywhere else it would stamp an unrelated SHA. That is why it lives in bb.edn
  rather than in a launcher — a payload copied into a stranger's project should
  not carry a command that is wrong by construction there.

  It covers all three colours in one pass. Stamping only one is how a fix ships
  green while red and blue keep advertising the commit before it, which is
  exactly what happened when this task owned `package-once-green/green` alone.

  Optional GREEN_LIB_ROOT, RED_LIB_ROOT, BLUE_LIB_ROOT and COMPUTE_LIB_ROOT
  checkouts also stamp their published HEADs into the bundled launchers."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]))

(def ^:private once-repo "getcolors/once")

(def ^:private pin-sites
  "Every place a copied payload records which `once` commit to resolve.

  One capture group per pattern, holding the SHA and nothing else. `guard`
  below fails when a payload carries an `once` commit that no pattern here
  claims, so adding a fourth colour cannot silently skip this list."
  [{:path "skills/package-once-green/green"
    :rx #"\(def \^:private once-sha \"([0-9a-f]{40})\"\)"}
   {:path "skills/package-once-blue/blue"
    :rx #"package-once-blue = \{ git = \"[^\"]*getcolors/once[^\"]*\", rev = \"([0-9a-f]{40})\""}
   {:path "skills/package-once-red/red"
    :rx #"\"package-once-red\": \"github:getcolors/once#([0-9a-f]{40})\""}])

(def ^:private green-site
  {:path "skills/package-once-green/green"
   :rx #"\(def \^:private green-sha \"([0-9a-f]{40})\"\)"})

(def ^:private dependency-sites
  [{:env "GREEN_LIB_ROOT" :label "green" :sites [green-site]}
   {:env "RED_LIB_ROOT" :label "red"
    :sites [{:path "skills/package-once-red/red"
             :rx #"\"red\": \"github:getcolors/red#([0-9a-f]{40})\""}]}
   {:env "BLUE_LIB_ROOT" :label "blue"
    :sites [{:path "skills/package-once-blue/blue"
             :rx #"# blue = \{ git = \"[^\"]*getcolors/blue[^\"]*\", rev = \"([0-9a-f]{40})\""}]}
   {:env "COMPUTE_LIB_ROOT" :label "colors-compute"
    :sites [{:path "skills/package-once-red/red"
             :rx #"\"colors-compute-red\": \"github:getcolors/colors-compute#([0-9a-f]{40})\""}
            {:path "skills/package-once-blue/blue"
             :rx #"# colors-compute-blue = \{ git = \"[^\"]*getcolors/colors-compute[^\"]*\", rev = \"([0-9a-f]{40})\""}]}])

(defn- git
  [dir & args]
  (apply sh/sh "git" "-C" (str dir) args))

(defn- git-out
  [dir & args]
  (let [{:keys [exit out]} (apply git dir args)]
    (when (zero? exit) (str/trim out))))

(defn- repo-head
  "HEAD of the repository containing `dir`, once it is clean and pushed.
  Returns [sha nil] or [nil error]. Pinning a dirty or unpushed commit would
  produce a launcher that resolves to something nobody else can fetch."
  [dir label]
  (if-let [top (git-out dir "rev-parse" "--show-toplevel")]
    (let [dirty (git-out top "status" "--porcelain")
          sha (git-out top "rev-parse" "HEAD")
          remotes (git-out top "branch" "-r" "--contains" (str sha))]
      (cond
        (seq dirty)
        [nil (str label " working tree is dirty; commit before pinning")]

        (not (str/includes? (str remotes) "origin/"))
        [nil (str label " HEAD " (subs sha 0 7) " is not on any remote branch; "
                  "push before pinning")]

        :else [sha nil]))
    [nil (str label " is not a git repository: " dir)]))

(defn- current-pin
  "The SHA `site` records today, or nil when its pattern does not match."
  [text {:keys [rx]}]
  (second (re-find rx text)))

(defn- replace-pin
  "Rewrite only the capture group, leaving the surrounding syntax untouched —
  each colour records its pin in a different file format."
  [text {:keys [rx]} sha]
  (let [m (re-matcher rx text)]
    (when (.find m)
      (str (subs text 0 (.start m 1)) sha (subs text (.end m 1))))))

(defn- guard
  "Names payload files carrying an `once` commit that no pin site claims.

  A payload that pins out of band is invisible to this command, so it keeps
  advertising an old commit while `pin` reports success — the failure this
  whole list exists to prevent."
  []
  (let [claimed (set (map :path pin-sites))]
    (->> (file-seq (io/file "skills"))
         (filter #(.isFile ^java.io.File %))
         (keep (fn [^java.io.File f]
                 (let [path (str/replace (.getPath f) "\\" "/")
                       text (slurp f)]
                   (when (and (not (claimed path))
                              (str/includes? text once-repo)
                              (re-find #"[0-9a-f]{40}" text))
                     path))))
         sort
         vec)))

(defn pin
  "Returns green's Unix-style outcome map, so the task can be tested."
  []
  (let [unclaimed (guard)
        sites (map (fn [{:keys [path] :as site}]
                     (let [file (io/file path)]
                       (assoc site
                              :exists? (.exists file)
                              :text (when (.exists file) (slurp file)))))
                   pin-sites)
        missing (->> sites (remove :exists?) (map :path) sort vec)
        unmatched (->> sites
                       (filter :exists?)
                       (remove #(current-pin (:text %) %))
                       (map :path)
                       sort
                       vec)
        [once-head once-err] (repo-head "." "once")
        dependencies (vec (for [{:keys [env label sites]} dependency-sites
                                :let [root (System/getenv env)] :when root
                                :let [[sha error] (repo-head root label)]]
                            {:label label :sha sha :error error :sites sites}))
        dependency-error (some :error dependencies)
        dependency-unmatched (for [{:keys [sites]} dependencies site sites
                                   :when (not (current-pin (slurp (:path site)) site))]
                               (:path site))]
    (cond
      (seq unclaimed)
      {:green/exit 2
       :green/err (str "these payloads pin a " once-repo " commit that `pin` does not manage: "
                       (str/join ", " unclaimed)
                       "\nadd them to pin-sites, or the next fix ships without them")}

      (seq missing)
      {:green/exit 2 :green/err (str "pin site is missing: " (str/join ", " missing))}

      (seq unmatched)
      {:green/exit 2 :green/err (str "could not locate the pin in " (str/join ", " unmatched))}

      once-err {:green/exit 2 :green/err once-err}
      dependency-error {:green/exit 2 :green/err dependency-error}
      (seq dependency-unmatched)
      {:green/exit 2 :green/err (str "could not locate dependency pin in " (str/join ", " dependency-unmatched))}

      :else
      (let [stale (filter #(not= once-head (current-pin (:text %) %)) sites)
            dependency-stale (for [{:keys [label sha sites]} dependencies site sites
                                   :let [old (current-pin (slurp (:path site)) site)]
                                   :when (not= sha old)]
                               {:label label :sha sha :old old :site site})]
        (if-not (or (seq stale) (seq dependency-stale))
          {:green/exit 0 :green/err (str "already pinned to " (subs once-head 0 7))}
          (do
            (doseq [{:keys [path text] :as site} stale]
              (spit path (replace-pin text site once-head)))
            (doseq [{:keys [site sha]} dependency-stale]
              ;; Re-read: another pin in this payload may have just changed.
              (spit (:path site) (replace-pin (slurp (:path site)) site sha)))
            {:green/exit 0
             :green/err (str/join
                         "\n"
                         (concat (mapv #(str "pinned " (:path %) " to " (subs once-head 0 7)
                                             " (was " (subs (current-pin (:text %) %) 0 7) ")")
                                       stale)
                           (map (fn [{:keys [label sha old]}]
                                  (str "pinned " label " to " (subs sha 0 7)
                                       " (was " (subs old 0 7) ")")) dependency-stale)))}))))))

(defn -main
  [& _]
  (let [{:green/keys [exit err]} (pin)]
    (when err
      (binding [*out* (if (zero? exit) *out* *err*)]
        (println err)))
    (System/exit exit)))
