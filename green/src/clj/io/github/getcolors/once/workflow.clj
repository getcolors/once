(ns io.github.getcolors.once.workflow
  "The DAG the launcher runs, and the two steps that are not a tool.

  Create and build join compute and SMTP at DNS, then run local SSH setup
  before remote application convergence. A guarded real create verifies
  recorded compute ownership before either parallel provider branch starts.
  Delete removes local aliases before destroying compute."

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

(defn- adopt-existing-state
  "Delete renders the same templates as create, so a destroy needs the params
  earlier stages produced (compute ip, smtp domain id and records)."
  [opts]
  (let [loaded (machine/load-inventory opts)]
    (if (wf/failed? loaded) loaded
        (let [smtp (state-output opts "tofu-smtp")]
          (cond-> loaded smtp (-> (merge smtp) (assoc :once/smtp-params smtp)))))))

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
       (if (and real? (= :delete event))
         (adopt-existing-state opts)
         (let [checked (if (and real? (= :create event) (true? (:compute-require-existing-state opts)))
                         (machine/load-inventory opts env) opts)]
           (if (wf/failed? checked) checked (with-deploy-keys checked real?)))))}
    env)))

(defn ansible-cleanup-step
  "Undo what the Ansible stages applied, then remove their rendered trees.
  ansible-local runs its playbook once more to drop the managed ~/.ssh/config
  block; both steps then scaffold against :green/event :delete, which deletes
  their targets."
  [opts]
  (-> opts tools/ansible-local-step tools/ansible-remote-step))

;; ---------------------------------------------------------------------------
;; wiring

(def tofu-steps
  [:once/tofu-compute :once/tofu-smtp :once/tofu-dns :once/tofu-smtp-post])

(def side-effecting-steps
  (into tofu-steps [:once/ansible-local :once/ansible-remote
                    :once/ansible-cleanup :once/github :once/ssh-cleanup]))

(defn wire-fn
  [step run-opts]
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
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp :once/tofu-compute]
      :once/tofu-smtp       [tools/tofu-smtp-step]
      ;; The local keypair goes last, strictly after a successful compute
      ;; destroy: a failed delete leaves the key, which is still the only
      ;; credential to whatever survived.
      :once/tofu-compute    [tools/tofu-compute-step]
      :once/ssh-cleanup     [ssh/cleanup-step])
    (case step
      :once/start           [start-step :once/tofu-compute]
      :once/tofu-compute    [tools/tofu-compute-step :once/tofu-smtp]
      :once/tofu-smtp       [tools/tofu-smtp-step :once/tofu-dns]
      :once/tofu-dns        [tools/tofu-dns-step :once/tofu-smtp-post]
      :once/tofu-smtp-post  [tools/tofu-smtp-post-step :once/ansible-local]
      :once/ansible-local   [tools/ansible-local-step :once/ansible-remote]
      ;; Publishing follows the remote stage, not the local one: the
      ;; credentials describe a configured host, and a workstation-side failure
      ;; should not gate them.
      :once/ansible-remote  [tools/ansible-remote-step :once/github]
      :once/github          [github/github-step])))

;; ---------------------------------------------------------------------------
;; backends

(defn backend-advice
  "The `:before` advice that writes backend.tf.json for one stage. Remote state
  is keyed by profile and stage, so two profiles never share a state file."
  [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (or (:profile %) "default") "/" tool ".tfstate")}))

(def workflow
  (-> (wf/workflow {:start :once/start :wire-fn wire-fn})
      (wf/advice-add :once/tofu-smtp :before ::backend
                     (backend-advice "tofu-smtp"))
      (wf/advice-add :once/tofu-dns :before ::backend
                     (backend-advice "tofu-dns"))
      (wf/advice-add :once/tofu-smtp-post :before ::backend
                     (backend-advice "tofu-smtp-post"))
      progress/advise
      (dry-run/advise side-effecting-steps)))
