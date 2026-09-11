(ns fueldepot.governor
  "Fuel Depot Operations Governor -- the independent compliance layer
  that earns the FuelDepotAdvisor the right to commit. The advisor has
  no notion of whether a depot it wants to schedule a delivery
  operation against has actually had its operating license
  inspected/registered, whether a supplier order it wants to
  coordinate has actually stayed under a safe procurement-value
  threshold, whether a delivery proposal secretly tries to finalize a
  tank/pipeline-integrity decision or a hazmat-storage-safety
  clearance (rather than merely draft-schedule a delivery), whether a
  delivery proposal's own claimed quantity would blow through the
  depot's own logged tank capacity, or when an act stops being a draft
  and becomes a real bulk-fuel delivery or a real supplier commitment,
  so this MUST be a separate system able to *reject* a proposal and
  fall back to HOLD.

  `:itonami.blueprint/governor` is `:fuel-depot-operations-governor`,
  grep-verified UNIQUE fleet-wide (`gh search code
  \"fuel-depot-operations-governor\" --owner cloud-itonami`, zero hits
  before this repo was created) -- distinct from
  `cloud-itonami-isic-4671`'s own `:fuel-trading-governor` (a
  counterparty-credit/sanctions/contract-clearance vertical for bulk
  fuel TRADING, not this depot's own back-office OPERATIONS
  coordination).

  CRITICAL SAFETY DESIGN NOTE (hazmat self-tripping-bug avoidance):
  every scope-exclusion check below tests a DEDICATED, explicitly-named
  BOOLEAN FLAG on the proposal's own `:value`
  (`:finalize-tank-integrity-clearance?` /
  `:finalize-hazmat-storage-safety-clearance?`), the SAME discipline
  `adhesivemfg.governor`'s own `:actuate-line?`/`:decide-certification?`
  flags establish -- NEVER a substring/term-list scan over the
  advisor's own free-text `:summary`/`:rationale`. A sibling-fleet bug
  class has recurred where a governor's own scope-exclusion term list
  was phrased as a bare noun (e.g. bare 'safety') and accidentally
  matched inside the mock advisor's own DEFAULT rationale/disclaimer
  text for a legitimate, allowed proposal, self-tripping the actor on
  its own happy path. Because these checks are boolean-flag-based, not
  text-search-based, and because every flag name below is phrased as
  the FINALIZATION/EXECUTION ACTION ('finalize the tank-integrity
  clearance', 'finalize the hazmat-storage-safety clearance') rather
  than a bare noun that a rationale sentence could innocently contain
  ('...tank integrity...', '...safety...'), this bug class is
  structurally impossible here. `fueldepot.governor-contract-test`'s
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion-
  checks` test asserts this directly across every op's own default
  advisor proposal.

  Five checks, in priority order, ALL HARD violations except the
  confidence/high-value gate (SOFT -- asks a human to look, and the
  human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else --
                                       HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:pipeline-valve/
                                       open` or `:tank/actuate`) is the
                                       'direct tank/pipeline-equipment
                                       control' scope violation this
                                       actor must NEVER perform --
                                       HARD, PERMANENT, unconditional.
    4. Tank/pipeline-integrity-
       clearance-finalize blocked   -- ANY proposal (regardless of op)
                                       whose own `:value` declares
                                       `:finalize-tank-integrity-
                                       clearance? true` -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `fueldepot.phase`: no op is ever
                                       eligible to carry this flag
                                       through to a commit).
    5. Hazmat-storage-safety-
       clearance-finalize blocked   -- ANY proposal (regardless of op)
                                       whose own `:value` declares
                                       `:finalize-hazmat-storage-
                                       safety-clearance? true` -- HARD,
                                       PERMANENT, unconditional. The
                                       domain-specific twin of check 4:
                                       deciding or granting a hazmat-
                                       storage-safety clearance is
                                       EXCLUSIVELY a safety-certification
                                       authority's call, never this
                                       actor's.
    6. Confidence floor / high-
       value gate                    -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes` (ALWAYS set for
                                       `:flag-safety-concern`), OR (for
                                       `:coordinate-supplier-order`) the
                                       proposal's own `:value` declares
                                       an `:order-value-usd`
                                       INDEPENDENTLY re-derived to be at
                                       or above
                                       `fueldepot.registry/default-
                                       order-value-threshold-usd` --
                                       escalate to a human depot
                                       supervisor / procurement
                                       approver. SOFT: the human may
                                       approve.

  Two more guards, double-delivery/double-order prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-scheduled-violations`/
  `already-ordered-violations` refuse to schedule/coordinate the SAME
  delivery-operation/supplier-order twice, off dedicated
  `:scheduled?`/`:ordered?` facts (never a `:status` value) -- the SAME
  'check a dedicated boolean, not status' discipline every prior
  governor's guards establish."
  (:require [fueldepot.registry :as registry]
            [fueldepot.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-shipment-record :schedule-delivery-operation
    :flag-safety-concern :coordinate-supplier-order})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  tank/pipeline-equipment-control effect and NEVER a hazmat-storage-
  safety-clearance or tank/pipeline-integrity-decision effect."
  #{:shipment-record/log :delivery-operation/schedule
    :safety-concern/flag :supplier-order/coordinate})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence -- a 'flag a concern' op must
  always escalate and never be auto-commit-eligible, matching
  `fueldepot.phase`'s own `:auto` set (which never contains
  `:flag-safety-concern`)."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- tank-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct tank/pipeline-equipment control, a fabricated
  actuation effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :tank-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") はタンク/パイプライン設備の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- tank-integrity-clearance-finalize-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal whose own `:value`
  declares `:finalize-tank-integrity-clearance? true` is attempting to
  have this actor directly FINALIZE a tank/pipeline-integrity decision
  -- this actor may only ever propose/schedule/coordinate a DRAFT
  record, never finalize a tank/pipeline-integrity clearance. No
  override, ever. See ns docstring's 'CRITICAL SAFETY DESIGN NOTE' --
  this is a boolean-flag check, never a text-search over the advisor's
  own rationale."
  [proposal]
  (when (true? (:finalize-tank-integrity-clearance? (:value proposal)))
    [{:rule :tank-integrity-clearance-finalize-blocked
      :detail "タンク/パイプラインの完全性(integrity)クリアランスの確定は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- hazmat-storage-safety-clearance-finalize-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal whose own `:value`
  declares `:finalize-hazmat-storage-safety-clearance? true` is
  attempting to have this actor DECIDE or GRANT a hazmat-storage-safety
  clearance -- that decision is EXCLUSIVELY a safety-certification
  authority's call, never this actor's. The domain-specific twin of
  `tank-integrity-clearance-finalize-blocked-violations`: same
  permanence, same unconditional block, no phase or human approval
  override, ever."
  [proposal]
  (when (true? (:finalize-hazmat-storage-safety-clearance? (:value proposal)))
    [{:rule :hazmat-storage-safety-clearance-finalize-blocked
      :detail "危険物貯蔵安全(hazmat-storage-safety)クリアランスの確定は認証機関の専権事項であり、この actor が代行することは恒久的に禁止"}]))

(defn- depot-not-verified-violations
  "For `:schedule-delivery-operation` and `:coordinate-supplier-order`,
  INDEPENDENTLY verify the referenced depot's own `:verified?` AND
  `:registered?` are both true (`fueldepot.registry/depot-ready?`) --
  never trust the advisor's own rationale about verification/
  registration status. Grounded in this blueprint's own HARD invariant
  ('depot/license record must be independently verified/registered
  before any action'): no delivery may ever be scheduled, and no
  supplier order ever coordinated, against a depot whose own operating
  license has not actually been inspected or whose registration is not
  actually on file."
  [{:keys [op]} proposal st]
  (when (contains? #{:schedule-delivery-operation :coordinate-supplier-order} op)
    (let [depot-id (:depot-id (:value proposal))
          d (and depot-id (store/depot st depot-id))]
      (when-not (and d (registry/depot-ready? d))
        [{:rule :depot-not-verified
          :detail (str depot-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み事業所免許記録が無い状態での提案")}]))))

(defn- delivery-capacity-exceeded-violations
  "For `:schedule-delivery-operation`, INDEPENDENTLY recompute whether
  the depot's own recorded `:inventory-liters` plus the proposal's own
  claimed `:quantity-liters` would exceed the depot's own recorded
  `:tank-capacity-liters` (`fueldepot.registry/delivery-capacity-
  exceeded?`) -- ground truth from the depot's own permanent fields,
  never a self-reported quantity claim. Overfilling a storage tank is a
  real physical hazard, not merely an accounting overage."
  [{:keys [op]} proposal st]
  (when (= op :schedule-delivery-operation)
    (let [{:keys [depot-id quantity-liters]} (:value proposal)
          d (and depot-id (store/depot st depot-id))]
      (when (and d (registry/delivery-capacity-exceeded? d quantity-liters))
        [{:rule :delivery-capacity-exceeded
          :detail (str depot-id " の記録済みタンク容量(" (:tank-capacity-liters d)
                       "L)を、既存在庫(" (:inventory-liters d 0.0)
                       "L)+今回申請(" quantity-liters "L)が超過")}]))))

(defn- already-scheduled-violations
  "For `:schedule-delivery-operation`, refuses to schedule the SAME
  delivery-operation record twice, off a dedicated `:scheduled?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-delivery-operation)
    (when (store/delivery-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- already-ordered-violations
  "For `:coordinate-supplier-order`, refuses to coordinate the SAME
  supplier-order record twice, off a dedicated `:ordered?` fact (never
  a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :coordinate-supplier-order)
    (when (store/order-already-coordinated? st subject)
      [{:rule :already-ordered
        :detail (str subject " は既に発注調整済み")}])))

(defn- invalid-fuel-type-violations
  "For `:log-shipment-record`, if the patch declares a `:fuel-type`
  outside the closed known set, reject rather than let a fabricated
  fuel type through."
  [{:keys [op]} proposal]
  (when (= op :log-shipment-record)
    (let [fuel-type (:fuel-type (:value proposal))]
      (when (and (some? fuel-type) (not (registry/fuel-type-valid? fuel-type)))
        [{:rule :invalid-fuel-type
          :detail (str fuel-type " は既知の fuel-type 値ではない")}]))))

(defn- invalid-quantity-violations
  "For `:log-shipment-record`, if the patch declares a
  `:quantity-liters` that is not a physically plausible reading, reject
  rather than let fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-shipment-record)
    (let [q (:quantity-liters (:value proposal))]
      (when (and (some? q) (not (registry/quantity-valid? q)))
        [{:rule :invalid-quantity
          :detail (str q "L は物理的に妥当な数量の範囲外")}]))))

(defn- high-value-order-violations
  "SOFT trigger (folded into `stakes?`, not a HARD violation): for
  `:coordinate-supplier-order`, INDEPENDENTLY re-derive whether the
  proposal's own declared `:order-value-usd` is at or above
  `fueldepot.registry/default-order-value-threshold-usd` -- never taken
  on the advisor's self-reported `:stake` alone. Returns a boolean, not
  a violation vector (this function feeds `stakes?`, matching
  `high-stakes`'s own role in `check` below)."
  [{:keys [op]} proposal]
  (and (= op :coordinate-supplier-order)
       (registry/order-value-exceeds-threshold? (:order-value-usd (:value proposal)))))

(defn check
  "Censors a FuelDepotAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (tank-control-blocked-violations proposal)
                           (tank-integrity-clearance-finalize-blocked-violations proposal)
                           (hazmat-storage-safety-clearance-finalize-blocked-violations proposal)
                           (depot-not-verified-violations request proposal st)
                           (delivery-capacity-exceeded-violations request proposal st)
                           (already-scheduled-violations request st)
                           (already-ordered-violations request st)
                           (invalid-fuel-type-violations request proposal)
                           (invalid-quantity-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (or (boolean (high-stakes (:stake proposal)))
                    (boolean (high-value-order-violations request proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
