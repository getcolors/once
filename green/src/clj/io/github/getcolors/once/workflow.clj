(ns io.github.getcolors.once.workflow
  "The DAG the launcher runs, and the two steps that are not a tool.

  Create and build run compute, SMTP, DNS, local SSH setup, and remote
  application convergence in order. A guarded real create verifies recorded
  compute ownership before generating application deploy keys. Delete removes
  local aliases and application resources before retiring compute ownership."

  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [green.cli :as green-cli]
   [green.dry-run :as dry-run]
   [green.lifecycle :as lifecycle]
   [green.progress :as progress]
   [green.tofu :as tofu]
   [green.workflow :as wf]
   [io.github.getcolors.once.machine :as machine]
   [io.github.getcolors.once.access :as access]
   [io.github.getcolors.once.github :as github]
   [io.github.getcolors.once.ssh :as ssh]
   [io.github.getcolors.once.tools :as tools]
   [io.github.getcolors.once.utils :as utils]
   [io.github.getcolors.once.validate :as validate]))

;; ---------------------------------------------------------------------------
;; start

(defn- state-output
  "Read a previously applied stage's `params` output, or nil when the stage has
  no state yet."
  [opts tool]
  (try
    (some-> (tofu/outputs (tools/tool-dir opts tool)
                          (tools/backend-credential-env opts))
            :params walk/keywordize-keys)
    (catch Exception _ nil)))

(defn- with-deploy-keys
  "Attach the keys `ansible-remote` installs and the `github` step publishes.

  Generating them is a create-time side effect, so a build or a dry-run takes
  fixed placeholders instead: a fresh key rendered into the artifact would make
  the build nondeterministic and break byte parity between the colours."
  [opts real?]
  (if (and real? (= :create (:green/event opts)))
    (let [[keys err] (github/generate-keys opts)]
      (if err
        (assoc opts :green/exit 1 :green/err err)
        (assoc opts
               :green/exit 0
               :once/deploy-keys keys
               :once/key-dir (some-> (first keys) :private-file io/file .getParent))))
    (assoc opts :green/exit 0 :once/deploy-keys (github/placeholder-keys opts))))

(defn start-step
  "Overlay `COLORS_PAR_*`, validate, and — for a real delete — read back what
  the earlier stages left in OpenTofu state.

  Credentials are only required for a lifecycle event that actually reaches a
  provider: `build` and `--dry-run` render from desired state alone, so they
  stay usable without any secret in the environment.

  The two-argument arity takes the environment to overlay, so a test does not
  inherit whatever `COLORS_PAR_*` variables the developer happens to have set."
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts
    {:defaults {:compute-prevent-destroy true}
     :overlay green-cli/read-pars
     :validators
     [(fn [opts _ _] (validate/state-errors opts))
      (fn [opts _ {:keys [event real?]}]
        (when (and real? (contains? #{:create :delete} event))
          (validate/secret-errors opts)))
      (fn [opts _ {:keys [event real?]}]
        (when (and real? (= :delete event) (:compute-prevent-destroy opts))
          [(str "compute destruction is protected; set "
                (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
     :after-validate
     (fn [opts env {:keys [event real?]}]
       (if (= :ssh-uninstall event) (assoc opts :green/exit 0)
       (let [real? (and real? (not= :build event))
             opts (cond-> opts (= :build event) (update :workdir #(str % "/build")))
             _ (when-not (:green/dry-run opts) (access/lock! opts))
             prepared (if (:green/dry-run opts) (assoc opts :once/ssh-resource machine/placeholder-resource) (access/resource-step opts))
             prepared (if (or (:green/dry-run opts) (wf/failed? prepared)) prepared (access/registration-step prepared))
             loaded (if (and real? (not (wf/failed? prepared))
                             (or (#{:delete :ssh :ssh-install} event) (:compute-require-existing-state opts)))
                      (machine/load-inventory prepared env) prepared)]
         (cond
           (wf/failed? loaded) loaded
           (:colors-compute/already-destroyed loaded) loaded
           (:once/registration-destroyed loaded)
           (assoc loaded :green/exit 1 :green/err "SSH registration is destroyed while compute is still present")
           :else
           (let [ready (if (or (= :create event) (= :build event) (= :ssh event))
                         (access/agent-step loaded) loaded)
                 ready (if (and real? (= :delete event))
                         (let [smtp (state-output ready "tofu-smtp")]
                           (cond-> ready smtp (-> (merge smtp) (assoc :once/smtp-params smtp)))) ready)]
             (with-deploy-keys ready real?))))))}
    env)))

(defn ansible-cleanup-step
  "Undo what the Ansible stages applied, then remove their rendered trees.
  ansible-local runs its playbook once more to drop the managed ~/.ssh/config
  block; both steps then scaffold against :green/event :delete, which deletes
  their targets."
  [opts]
  (let [local (tools/ansible-local-step opts)]
    (if (wf/failed? local) local (tools/ansible-remote-step local))))

;; ---------------------------------------------------------------------------
;; wiring

(def tofu-steps
  [:once/tofu-compute :once/tofu-smtp :once/tofu-dns :once/tofu-smtp-post])

(def side-effecting-steps
  (into tofu-steps [:once/ansible-local :once/ansible-remote
                    :once/ansible-cleanup :once/github :once/ssh-cleanup :once/registration-delete :once/ssh :once/ssh-install :once/ssh-uninstall]))

(defn wire-fn
  [step run-opts]
  (if (#{:ssh :ssh-install :ssh-uninstall} (:green/event run-opts))
    (let [target (keyword "once" (name (:green/event run-opts)))]
      (case step
        :once/start [start-step target]
        :once/ssh [access/ssh-step]
        :once/ssh-install [access/install-step]
        :once/ssh-uninstall [access/uninstall-step]))
  (if (= :delete (:green/event run-opts))
    (case step
      ;; Revoking runs before anything is destroyed: a withdrawn credential
      ;; against a live host is a loud, recoverable broken deploy, while a live
      ;; credential against a destroyed host is silent. It needs no key
      ;; material, so it also works when the box is already gone.
      :once/start           [start-step :once/github]
      :once/github          [github/github-step :once/ansible-cleanup]
      :once/ansible-cleanup [ansible-cleanup-step :once/tofu-smtp-post]
      :once/tofu-smtp-post  [tools/tofu-smtp-post-step :once/tofu-dns]
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp]
      :once/tofu-smtp       [tools/tofu-smtp-step :once/tofu-compute]
      ;; Registration follows compute. Encrypted SSH authority is retained.
      :once/tofu-compute    [tools/tofu-compute-step :once/registration-delete]
      :once/registration-delete [access/registration-delete-step]
      :once/ssh-cleanup     [ssh/cleanup-step])
    (case step
      :once/start           [start-step :once/tofu-compute]
      :once/tofu-compute    [tools/tofu-compute-step :once/tofu-smtp]
      :once/tofu-smtp       [tools/tofu-smtp-step :once/tofu-dns]
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp-post]
      :once/tofu-smtp-post  [tools/tofu-smtp-post-step :once/ansible-local]
      :once/ansible-local   [tools/ansible-local-step :once/ansible-remote]
      ;; Publishing follows the remote stage, not the local one: the
      ;; credentials describe a host whose local access and remote configuration succeeded.
      :once/ansible-remote  [tools/ansible-remote-step :once/github]
      :once/github          [github/github-step]))))

;; ---------------------------------------------------------------------------
;; backends

(defn backend-advice
  "The `:before` advice that writes backend.tf.json for one stage. Remote state
  is keyed by profile and stage, so two profiles never share a state file."
  [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (or (:profile %) "default") "/" tool ".tfstate")}))

(defn next-steps [step successors opts]
  (cond
    (wf/failed? opts) []
    (and (= step :once/start) (= :delete (:green/event opts)) (:colors-compute/already-destroyed opts))
    (if (and (machine/registration? opts) (not (:once/registration-destroyed opts)))
      [[:once/registration-delete opts]] [])
    :else (mapv #(vector % opts) successors)))

(def workflow
  (-> (wf/workflow {:start :once/start :wire-fn wire-fn :next-fn next-steps})
      (wf/advice-add :once/tofu-smtp :before ::backend
                     (backend-advice "tofu-smtp"))
      (wf/advice-add :once/tofu-dns :before ::backend
                     (backend-advice "tofu-dns"))
      (wf/advice-add :once/tofu-smtp-post :before ::backend
                     (backend-advice "tofu-smtp-post"))
      progress/advise
      (dry-run/advise side-effecting-steps)))
