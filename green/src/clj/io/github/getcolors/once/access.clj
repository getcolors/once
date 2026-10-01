(ns io.github.getcolors.once.access
  "V2 encrypted authority, profile ownership and scoped administrative access."
  (:require [clojure.java.io :as io] [clojure.string :as str] [green.cli :as cli] [green.scope :as scope]
            [green.process :as process]
            [io.github.getcolors.compute-local :as local]
            [io.github.getcolors.compute-node :as node]
            [io.github.getcolors.compute-ssh :as ssh]
            [io.github.getcolors.once.machine :as machine])
  (:import [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption LinkOption OpenOption]))
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
