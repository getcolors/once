(ns io.github.getcolors.once.deploy-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]))

(def deploy-script
  (.getAbsolutePath
   (io/file "src/resources/io/github/getcolors/once/tools/ansible/files/deploy")))

(defn- make-temp-dir! []
  (.toFile (java.nio.file.Files/createTempDirectory
            "deploy-test"
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn- make-shim!
  "Create a temp dir with `sudo` and `once` shims. The `once` shim logs every
  invocation to calls.log, returns `list-output` for `once list`, and exits 0
  for `once update` unless the host is `fail-host`."
  ([list-output] (make-shim! list-output nil))
  ([list-output fail-host]
   (let [dir       (make-temp-dir!)
         sudo      (io/file dir "sudo")
         once      (io/file dir "once")
         log       (io/file dir "calls.log")
         list-file (io/file dir "list.output")]
     (spit list-file list-output)
     (spit sudo (str "#!/bin/sh\n"
                     "[ \"$1\" = /usr/local/bin/once-deploy-app ] || exit 2\n"
                     "echo \"update $2\" >> " (.getAbsolutePath log) "\n"
                     "[ \"$2\" = \"" (or fail-host "\\0") "\" ] && exit 1\n"
                     "grep -Fq \"$2\" " (.getAbsolutePath list-file) "\n"))
     (.setExecutable sudo true false)
     (spit once (str "#!/bin/sh\n"
                     "echo \"$@\" >> " (.getAbsolutePath log) "\n"
                     "case \"$1\" in\n"
                     "  list)   cat " (.getAbsolutePath list-file) " ;;\n"
                     "  update) [ \"$2\" = \"" (or fail-host "\\0") "\" ] && exit 1 ; exit 0 ;;\n"
                     "  *)      exit 2 ;;\n"
                     "esac\n"))
     (.setExecutable once true false)
     {:dir (.getAbsolutePath dir)
      :log (.getAbsolutePath log)})))

(def ^:private default-permitted ["bigconfig.website"])

(defn- run-deploy
  "The permitted hosts arrive as arguments, the way the ForceCommand in
  authorized_keys supplies them — one key, one repository, every host that
  repository serves."
  ([ssh-original-command] (run-deploy ssh-original-command nil default-permitted))
  ([ssh-original-command shim] (run-deploy ssh-original-command shim default-permitted))
  ([ssh-original-command shim permitted]
   (let [env (cond-> {"SSH_ORIGINAL_COMMAND" (or ssh-original-command "")}
               shim (assoc "PATH" (str (:dir shim) ":" (System/getenv "PATH"))))]
     (process/run (into ["bb" deploy-script] permitted)
                  {:extra-env env}))))

(deftest a-ping-updates-the-permitted-host
  (testing "the client sends nothing; the entry already names what to update"
    (let [shim (make-shim! "bigconfig.website (running)\nother.example.com (running)\n")
          {:keys [exit]} (run-deploy "" shim)
          log (slurp (:log shim))]
      (is (= 0 exit))
      (is (str/includes? log "update bigconfig.website"))
      (is (not (str/includes? log "update other.example.com"))
          "a ping updates this key's hosts, not every host on the box"))))

(deftest a-ping-updates-every-host-in-the-entry
  (testing "one repository serving several hosts updates all of them"
    (let [shim (make-shim! "a.example.com (running)\nb.example.com (running)\nc.example.com (running)\n")
          {:keys [exit]} (run-deploy "" shim ["a.example.com" "b.example.com"])
          log (slurp (:log shim))]
      (is (= 0 exit))
      (is (str/includes? log "update a.example.com"))
      (is (str/includes? log "update b.example.com"))
      (is (not (str/includes? log "update c.example.com"))))))

(deftest a-requested-command-is-ignored-not-obeyed
  (testing "an old workflow still sending `sudo once update <host>` keeps working"
    (let [shim (make-shim! "bigconfig.website (running)\nother.example.com (running)\n")
          {:keys [exit err]} (run-deploy "sudo once update other.example.com" shim)
          log (slurp (:log shim))]
      (is (= 0 exit))
      (is (str/includes? err "ignoring the requested command"))
      (is (str/includes? log "update bigconfig.website"))
      (is (not (str/includes? log "update other.example.com"))
          "the requested host is not what the key is for, so it is not updated"))))

(deftest an-arbitrary-command-selects-nothing
  (testing "restrict plus the forced command mean the string was never a lever"
    (let [shim (make-shim! "bigconfig.website (running)\n")
          {:keys [exit]} (run-deploy "rm -rf /" shim)
          log (slurp (:log shim))]
      (is (= 0 exit))
      (is (str/includes? log "update bigconfig.website"))
      (is (not (str/includes? log "rm"))))))

(deftest rejects-a-key-with-no-permitted-hosts
  (testing "an entry missing its ForceCommand arguments grants nothing"
    (let [{:keys [exit err]} (run-deploy "" nil [])]
      (is (= 1 exit))
      (is (str/includes? err "no permitted hosts configured")))))

(deftest rejects-shell-metacharacters-in-a-permitted-host
  (testing "a corrupted entry fails closed rather than reaching a shell"
    (let [{:keys [exit err]} (run-deploy "" nil ["foo;bar"])]
      (is (= 1 exit))
      (is (str/includes? err "invalid permitted host")))))

(deftest a-host-once-does-not-serve-fails-without-stopping-the-rest
  (let [shim (make-shim! "a.example.com (running)\n")
        {:keys [exit err]} (run-deploy "" shim ["a.example.com" "bogus.example.com"])
        log (slurp (:log shim))]
    (is (= 1 exit))
    (is (str/includes? err "once update failed for bogus.example.com"))
    (is (str/includes? err "failed: bogus.example.com"))
    (is (str/includes? log "update a.example.com")
        "one missing application does not block the rest of the repository")))

(deftest a-failed-update-fails-the-run
  (testing "the exit code is the deploy result for every host this key owns"
    (let [shim (make-shim! "a.example.com (running)\nb.example.com (running)\n" "b.example.com")
          {:keys [exit err]} (run-deploy "" shim ["a.example.com" "b.example.com"])
          log (slurp (:log shim))]
      (is (= 1 exit))
      (is (str/includes? err "once update failed for b.example.com"))
      (is (str/includes? err "failed: b.example.com"))
      (is (str/includes? log "update a.example.com")))))

(deftest parses-host-list-with-ansi-escapes
  (let [shim (make-shim! (slurp (io/resource "ansi.output")))
        {:keys [exit]} (run-deploy "" shim ["foo.bigconfig.space"])
        log (slurp (:log shim))]
    (is (= 0 exit))
    (is (str/includes? log "update foo.bigconfig.space"))))
