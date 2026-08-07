(ns io.github.getcolors.once.utils
  "The compatibility contract, and the DNS zone derivation every stage shares."
  (:require
   [clojure.string :as str]))

(def contract
  "Compatibility number for the launcher that consumes these namespaces and the
  templates under src/resources. Bump it on any change a launcher pinned to an
  older commit could not survive; the launcher refuses to run against a lower
  number and tells the user to repin.

  2: tools/backend-credential-env, which the launcher calls to read Tofu state.
  3: desired state drops :domain and :package. DNS zones are derived from the
     application hosts, and :profile alone names the stack.
  4: applications may span DNS zones. utils/apps-domains replaces the singular
     apps-domain contract used by the SMTP and DNS stages.
  5: validation and the workflow graph move out of the launcher and into this
     library, as once.validate and once.workflow. The launcher no longer
     defines its own steps — it calls workflow/workflow, describe/describe-file,
     and green.cli/read-pars, and `pin` is a maintainer bb task rather than a
     launcher subcommand.
  6: the Clojure package moves under the monorepo's green/ dependency root;
     launchers must resolve that root. Portable ONCE_PAR_* aliases and the
     shared byte-compatible Ansible lookup are also introduced.
  7: one parameter namespace, COLORS_PAR_*, replaces the portable alias and
     every colour-native prefix; desired state is a single colors.yml found by
     walking up from the working directory; the shared work directory is
     .colors. A launcher pinned older reads a file that is no longer there and
     resolves credentials under names nothing sets.
  8: deploy keys are generated per application on every create instead of being
     supplied as :deploy-pubkey, and an application may name a GitHub repository
     whose environment receives the connection details. A launcher pinned older
     still expects :deploy-pubkey in desired state and would ignore :github
     silently, publishing nothing.
  9: :oci-image-id pins the compute image. A launcher pinned older renders the
     image data source regardless and takes whatever Canonical published most
     recently, so the pin is ignored without a word and a plan proposes
     replacing the instance.
  10: the Clojure namespaces move from io.github.bigconfig-ai.once.* to
     io.github.getcolors.once.*, following the repository's move to the
     getcolors org. The launcher resolves these names directly, so one pinned
     older cannot find them. The rename also changes generated Terraform
     resource addresses, which existing stacks must migrate with
     `tofu state mv` before their next apply -- see README.md.
  11: :yandex-image-id pins the compute image, and the family path stops
     tracking the resolved id (ignore_changes on the boot disk image). A
     launcher pinned older renders the family lookup without the ignore, so a
     Yandex release plans a replacement of the server and prevent_destroy
     turns that plan into a failed apply, blocking unrelated deploys."
  11)

(defn registrable-domain
  "The DNS zone `host` belongs to: its last two labels. Multi-label suffixes
  such as co.uk are not recognised — a host under one has to sit in a zone
  Cloudflare would report by its last two labels anyway."
  [host]
  (let [labels (str/split (str host) #"\.")]
    (when (<= 2 (count labels))
      (str/join "." (take-last 2 labels)))))

(defn apps-domains
  "The sorted DNS zones used by the applications. Desired state carries no
  :domain: each application's DNS records, Resend sending domain, and From
  address derive from that application's host."
  [opts]
  (->> (get-in opts [:once :applications])
       (keep (comp registrable-domain :host))
       distinct
       sort
       vec))
