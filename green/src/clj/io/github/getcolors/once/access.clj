(ns io.github.getcolors.once.access
  "V2 encrypted authority, profile ownership and scoped administrative access."
  (:require [cheshire.core :as json] [clojure.java.io :as io] [clojure.string :as str] [green.cli :as cli] [green.scope :as scope]
            [green.process :as process]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.compute-node :as node]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.once.machine :as machine])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files FileAlreadyExistsException StandardOpenOption LinkOption OpenOption]))
(def ^:dynamic *register!* nil)
(defn scoped [f]
  (scope/with-scope (fn [register!] (binding [*register!* register!] (f)))))
(defn run-cli [workflow args] (scoped #(cli/run-cli workflow args)))
(defn lock! [opts]
  (when-not *register!* (throw (ex-info "ONCE runtime requires an access scope" {})))
  (let [root (machine/sdk-workdir opts)
        directory (local/path (str (io/file root (:profile opts))))
        path (.resolve directory ".once.lock")]
    (local/private-owned-directory! root directory)
    (local/prepare! (str path))
    (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
      (try
        (local/prepare! (str path))
        (when-not (.tryLock channel) (throw (ex-info "another ONCE operation owns this profile" {})))
        (*register!* :resource #(.close channel))
        (catch Exception e (.close channel) (throw e))))))
(defn resource-step [opts]
  (let [existing? (.exists (io/file (machine/sdk-workdir opts) (:profile opts) machine/node-id "compute.tf.json"))
        operation (if (or existing? (:compute-require-existing-state opts) (not= :create (:green/event opts))) "inspect" "create")
        result (if (machine/planning? opts) machine/placeholder-resource
                   (ssh/ssh-resource! (machine/library-options opts) (machine/ssh-request opts) operation (System/getenv)))]
    (if (= "ready" (:status result)) (assoc opts :once/ssh-resource result :green/exit 0)
        (machine/failed-result opts result))))
(defn registration-step [opts]
  (if-not (machine/registration? opts) opts
    (let [operation (if (and (= :create (:green/event opts)) (not (:compute-require-existing-state opts))) "create" "inspect")
          result (if (machine/planning? opts)
                   (machine/canonical-build! opts (node/registration-plan (machine/library-options opts) (machine/registration-request opts)))
                   (node/compute-registration! (machine/library-options opts) (machine/registration-request opts) operation))]
      (case (:status result)
        "built" (assoc opts :once/ssh-registration (machine/placeholder-registration opts) :green/exit 0)
        "ready" (assoc opts :once/ssh-registration result :green/exit 0)
        ;; A synthetic registration is only used to inspect already-empty compute
        ;; state. Never converge or destroy a live node with this placeholder.
        "destroyed" (if (= :delete (:green/event opts))
                      (assoc opts :once/registration-destroyed true :once/ssh-registration (machine/placeholder-registration opts))
                      (machine/failed-result opts result))
        (machine/failed-result opts result)))))
(defn agent-step [opts]
  (if (machine/planning? opts)
    (assoc opts :ssh-private-key-path (machine/placeholder-key opts) :once/agent-socket "/home/build-placeholder/agent.sock")
    (let [agent (ssh/start-agent! [{:opts (machine/library-options opts) :request (machine/ssh-request opts)
                                  :resource (machine/resource opts)}] (System/getenv) *register!*)]
      (assoc opts :once/agent-socket (:socket agent)
             :ssh-private-key-path (get (:identities agent) (:reference (machine/resource opts)))))))
(defn registration-delete-step [opts]
  (if-not (machine/registration? opts) opts
    (let [result (node/compute-registration! (machine/library-options opts) (machine/registration-request opts) "delete")]
      (if (= "destroyed" (:status result)) (assoc opts :green/exit 0) (machine/failed-result opts result)))))
(defn identity-args [opts]
  (when-let [identity (:ssh-private-key-path opts)]
    ["-F" "/dev/null" "-o" "IdentityFile=none" "-i" identity "-o" "IdentitiesOnly=yes" "-o" (str "IdentityAgent=" (or (:once/agent-socket opts) "none"))
     "-o" "ForwardAgent=no" "-o" "ControlMaster=no" "-o" "ControlPersist=no" "-S" "none"]))
(defn ssh-args [opts]
  (when-not (every? #(and (string? %) (not (str/blank? %)))
                    [(:ip opts) (:user opts) (:ssh-private-key-path opts) (:once/agent-socket opts)])
    (throw (ex-info "SSH requires a resolved address, login and scoped identity" {})))
  (into ["ssh" "-p" "22" "-l" (:user opts)
         "-o" "StrictHostKeyChecking=accept-new"]
        (concat (identity-args opts) ["--" (:ip opts)])))
(defn ssh-step [opts]
  (let [result (process/run-inherit (ssh-args opts) {})]
    (assoc opts :green/exit (or (:exit result) 1))))

(defn install-lock!
  "Serialize durable exports and aliases across workdirs until workflow exit."
  ([opts] (install-lock! opts (or (System/getenv "HOME") (System/getProperty "user.home"))))
  ([opts home]
   (when-not *register!* (throw (ex-info "ONCE installation requires an access scope" {})))
   (when-not (and (string? (:profile opts)) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,62}" (:profile opts)))
     (throw (ex-info "invalid SSH deployment alias" {})))
   (try
    (let [directory (local/path (str (io/file home ".ssh")))
         path (.resolve directory (str ".once-install-" (:profile opts) ".lock"))
         nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
         owner (System/getProperty "user.name")
         safe-file! (fn []
                      (when-not (and (Files/isRegularFile path nofollow)
                                     (= owner (str (Files/getOwner path nofollow)))
                                     (= 1 (Files/getAttribute path "unix:nlink" nofollow)))
                        (throw (ex-info "unsafe ONCE installation lock" {}))))]
     (local/private-directory! directory)
     (when-not (= owner (str (Files/getOwner directory nofollow)))
       (throw (ex-info "unsafe SSH directory owner" {})))
     (Files/setPosixFilePermissions directory (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))
     (try (Files/createFile path (local/attrs "rw-------"))
          (catch FileAlreadyExistsException _ nil))
     (safe-file!)
     (let [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS]))]
       (try
         (safe-file!)
         (Files/setPosixFilePermissions path (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
         (let [lock (.tryLock channel)]
           (when-not lock (throw (ex-info "another ONCE operation owns this SSH installation" {})))
           (*register!* :resource #(.close channel)))
         (catch Exception e (.close channel) (throw e)))))
     (catch Exception e
       (cond
         (= "java.nio.channels.OverlappingFileLockException" (.getName (class e)))
         (throw (ex-info "another ONCE operation owns this SSH installation" {}))
         (#{"unsafe SSH directory owner" "another ONCE operation owns this SSH installation"} (.getMessage e)) (throw e)
         :else (throw (ex-info "unsafe ONCE installation lock" {})))))))

(defn export-directory [opts]
  (str (io/file (or (System/getenv "HOME") (System/getProperty "user.home"))
                ".ssh" "once" (:profile opts))))

(defn export-operation
  ([opts operation] (export-operation opts operation ssh/ssh-export!))
  ([opts operation run-fn]
   (run-fn (machine/library-options opts)
           (cond-> (machine/ssh-request opts)
             (:once/ssh-resource opts) (assoc :expected (:once/ssh-resource opts)))
           (export-directory opts) operation (if (= operation "install") (System/getenv)
                                 (select-keys (into {} (System/getenv)) ["PATH" "HOME" "TMPDIR"])))))

(defn installed-identity [opts]
  (when-not (machine/planning? opts)
    (install-lock! opts)
    (let [result (export-operation opts "inspect")]
      (case (:status result)
        "installed" (:private_key_file result)
        "absent" nil
        (throw (ex-info (or (get-in result [:error :message]) "Invalid installed SSH identity") {}))))))

(defn config-payload [opts mode identity]
  {:host_alias (:profile opts) :block_state mode :keygen true :installed true
   :identity_file (or identity "") :legacy_marker_prefix ""
   :ssh_hosts (if (= mode "absent") []
                 [{:name (:profile opts) :ip (:ip opts) :user (:user opts)}])})

(defn update-config
  ([payload] (update-config payload process/run))
  ([payload run-fn]
   (run-fn ["python3" "-c"
            (str "import io, sys\nsys.stdin = io.StringIO(sys.argv[1])\n"
                 (slurp (io/resource "io/github/getcolors/once/tools/ansible-local/ssh_config.py")))
            (json/generate-string payload)] {})))

(defn- config-failure [opts result]
  (assoc opts :green/exit (or (:exit result) 1)
         :green/err (str "SSH config update failed: " (:err result))))

(defn install-step
  ([opts] (install-step opts export-operation update-config))
  ([opts export-fn config-fn]
   (if (machine/planning? opts) opts
     ;; Validate collisions before retrieving/writing a durable key. A later
     ;; config failure deliberately leaves an owned, encrypted export for retry.
     (let [_ (install-lock! opts)
           preflight (config-fn (assoc (config-payload opts "present"
                                       (str (io/file (export-directory opts) "identity"))) :check_only true))]
       (if-not (zero? (or (:exit preflight) 1)) (config-failure opts preflight)
         (let [exported (export-fn opts "install")]
           (if-not (= "installed" (:status exported)) (machine/failed-result opts exported)
             (let [result (config-fn (config-payload opts "present" (:private_key_file exported)))]
               (if (zero? (or (:exit result) 1)) (assoc opts :green/exit 0)
                 (config-failure opts result))))))))))

(defn uninstall-step
  ([opts] (uninstall-step opts export-operation update-config))
  ([opts export-fn config-fn]
   (if (machine/planning? opts) opts
     (do
       (lock! opts)
       (install-lock! opts)
       (let [exported (export-fn opts "inspect")]
         (if-not (#{"installed" "absent"} (:status exported)) (machine/failed-result opts exported)
           ;; Drop aliases first: an interrupted removal never leaves them
           ;; pointing at a deleted key. No backend/provider access is needed.
           (let [result (config-fn (config-payload opts "absent" nil))]
             (if-not (zero? (or (:exit result) 1)) (config-failure opts result)
               (let [removed (export-fn opts "remove")]
                 (if (#{"removed" "absent"} (:status removed)) (assoc opts :green/exit 0)
                   (machine/failed-result opts removed)))))))))))
