(ns fueldepot.phase
  "Phase 0->3 staged rollout for the ISIC 4661 (wholesale of solid,
  liquid and gaseous fuels and related products) fuel-wholesale-depot
  operations coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- shipment-record logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-coordinate -- adds safety-concern flags and
                                    supplier-order coordination
                                    proposals, still approval.
    Phase 3  supervised-auto    -- adds delivery-operation scheduling
                                    (still always approval -- see
                                    below); governor-clean, high-
                                    confidence `:log-shipment-record`
                                    (no physical/financial risk) may
                                    auto-commit.

  `:schedule-delivery-operation` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Scheduling a real
  tanker-truck/pipeline delivery is the one act in this domain with
  physical consequence (a delivery operation means fuel actually moves
  into or out of a storage tank), it is always a human depot
  supervisor's call. `fueldepot.governor`'s
  `tank-integrity-clearance-finalize-blocked-violations` and
  `hazmat-storage-safety-clearance-finalize-blocked-violations` HARD-
  block finalize attempts unconditionally, and the confidence/
  high-value gate independently never lets `:flag-safety-concern`
  auto-commit either -- multiple independent layers agree on where
  this actor's authority ends. Like every prior sibling's phase-3
  `:auto` set, this domain has only ONE member
  (`:log-shipment-record`) -- no separate no-risk lifecycle distinct
  from ordinary record logging. A 'flag a concern' op must always
  escalate and never be in any phase's `:auto` set --
  `:flag-safety-concern` is absent from every phase's `:auto` set
  below, matching `fueldepot.governor/high-stakes`.")

(def write-ops
  #{:log-shipment-record :schedule-delivery-operation
    :flag-safety-concern :coordinate-supplier-order})

;; NOTE the invariant: `:schedule-delivery-operation` and
;; `:coordinate-supplier-order` are members of `write-ops`
;; (governor-gated like any write) but NEVER members of any phase's
;; `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                            :auto #{}}
   1 {:label "assisted-intake"     :writes #{:log-shipment-record}                         :auto #{}}
   2 {:label "assisted-coordinate" :writes #{:log-shipment-record :flag-safety-concern
                                             :coordinate-supplier-order}                    :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-shipment-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-delivery-operation`/`:flag-safety-concern`/
    `:coordinate-supplier-order` are never auto-eligible at any phase,
    so they always escalate once the governor clears them (or hold if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Fuel Depot Operations Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
