(ns io.github.getcolors.once.once-module-test
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [green.process :as process]))

(def module
  (.getAbsolutePath
   (io/file "src/resources/io/github/getcolors/once/tools/ansible/library/once")))

(def smtp-password "re_a_real_secret")
(def database-url "postgres://user:hunter2@db/app")

(defn- temp-dir!
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "once-module-test"
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn- failing-deploy-shim!
  "A `once` shim whose `list` reports nothing deployed and whose `deploy` fails
  with the whole invocation echoed back — the worst case for a leak."
  [dir]
  (let [once (io/file dir "once")]
    ;; the verb follows `-n <namespace>`, so scan rather than index
    (spit once (str "#!/bin/sh\n"
                    "for a in \"$@\"; do\n"
                    "  case \"$a\" in\n"
                    "    list)   exit 0 ;;\n"
                    "    deploy) echo \"once deploy failed: $@\" >&2; exit 3 ;;\n"
                    "  esac\n"
                    "done\n"
                    "exit 0\n"))
    (.setExecutable once true false)
    (.getAbsolutePath dir)))

(defn- run-module-args
  [args]
  (let [dir (temp-dir!)
        shim (failing-deploy-shim! dir)
        args-file (io/file dir "args.json")]
    (spit args-file (json/generate-string args))
    (process/run ["bb" module (.getAbsolutePath args-file)]
                 {:extra-env {"PATH" (str shim ":" (System/getenv "PATH"))}})))

(defn- run-module
  [app]
  (run-module-args {:applications [app]}))

(deftest a-failed-deploy-reports-no-secrets
  (let [{:keys [exit out]} (run-module {:host "www.example.com"
                                        :image "ghcr.io/example/site:latest"
                                        :smtp_password smtp-password
                                        :env [(str "DATABASE_URL=" database-url)
                                              "LOG_LEVEL=debug"]})
        result (json/parse-string out true)]
    (is (= 1 exit))
    (is (true? (:failed result)))

    (testing "neither secret survives anywhere in the module's output"
      (is (not (str/includes? out smtp-password)))
      (is (not (str/includes? out "hunter2"))))

    (testing "the reported argv keeps its shape, with values redacted"
      (let [cmd (:cmd result)]
        (is (= "***" (nth cmd (inc (.indexOf cmd "--smtp-password")))))
        (is (some #{"DATABASE_URL=***"} cmd) "the variable name is still readable")
        (is (some #{"LOG_LEVEL=***"} cmd) "non-secret env is redacted too, being indistinguishable")
        (is (some #{"--host"} cmd))
        (is (some #{"www.example.com"} cmd) "non-secret arguments are untouched")))

    (testing "stderr from the failing command is scrubbed as well"
      (is (str/includes? (:msg result) "once deploy failed"))
      (is (str/includes? (:msg result) "***")))))

(deftest check-mode-plans-without-deploying
  ;; The shim's `deploy` fails loudly, so a zero exit proves check mode never
  ;; ran it: `once list` is the only command a check run may execute.
  (let [{:keys [exit out]} (run-module-args
                            {:applications [{:host "www.example.com"
                                             :image "ghcr.io/example/site:latest"}]
                             :_ansible_check_mode true})
        result (json/parse-string out true)]
    (is (= 0 exit) "the failing deploy shim was never invoked")
    (is (true? (:changed result)))
    (is (= ["www.example.com"] (:would_deploy result)))
    (is (= [] (:would_remove result)))
    (is (= "" (get-in result [:diff :before])))
    (is (= "www.example.com" (get-in result [:diff :after])))))
