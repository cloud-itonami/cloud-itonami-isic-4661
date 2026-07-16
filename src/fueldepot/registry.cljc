(ns fueldepot.registry
  "Pure-function domain logic for the ISIC 4661 (wholesale of solid,
  liquid and gaseous fuels and related products) fuel-wholesale-DEPOT
  operations coordination actor -- depot license verification,
  delivery-capacity recompute, fuel-type validation, quantity
  plausibility validation, order-value threshold recompute, and draft
  delivery-operation/supplier-order record construction.

  This vertical has NO pre-existing `kotoba-lang/fueldepot`-style
  capability library to wrap (verified: no such repo exists; distinct
  from `cloud-itonami-isic-4671`'s own fuel-TRADING vertical, which is
  itself self-contained rather than wrapping a library -- see that
  repo's `deps.edn` comment). The domain logic therefore lives here as
  pure functions, re-verified INDEPENDENTLY by `fueldepot.governor` --
  the same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes (most directly
  `adhesivemfg.registry` from `cloud-itonami-isic-2029`, this actor's
  closest 'plant/depot operations coordination, two-op verified/
  registered gate, physical-quantity recompute' structural analog):
  never trust a proposal's own self-reported quantity/order-value/
  fuel-type when the inputs needed to recompute it independently are
  already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real depot-operations system, and NO hazmat-storage-
  safety-clearance or tank/pipeline-integrity CERTIFICATION AUTHORITY
  whatsoever. It builds the DRAFT record a depot coordinator would keep
  (a scheduled tanker-truck/pipeline delivery, a coordinated supplier
  order), not the act of directly controlling a tank/pipeline valve,
  finalizing a hazmat-storage-safety clearance, or finalizing a
  tank/pipeline-integrity decision (this actor NEVER does any of these
  -- see README `What this actor does NOT do`).

  SCOPE note: ISIC 4661 is downstream fuel WHOLESALE at a depot/rack --
  distinct from `cloud-itonami-isic-4671`'s fuel-TRADING vertical
  (counterparty credit clearance / sanctions screening / contract-on-
  file before a bulk delivery dispatch or invoice settlement). This
  build models the DEPOT's own operations-coordination shape instead:
  a fixed storage-tank facility under an operating LICENSE, receiving
  and dispatching solid (coal/coke), liquid (petrol/diesel/kerosene/
  heavy fuel oil), and gaseous (LPG/CNG) fuel products, scheduling
  inbound/outbound tanker-truck or pipeline delivery operations, and
  coordinating supplier procurement orders to restock depot inventory."
  )

;; ----------------------------- constants -----------------------------

(def valid-fuel-types
  "The closed set of fuel-type values a shipment-record patch or
  delivery-operation may declare -- solid, liquid, and gaseous fuels
  and related products, per ISIC 4661's own class description.
  Anything else is a fabricated/unrecognized fuel type -- the governor
  HARD-holds rather than let an invented fuel type pass through."
  #{:coal :coke :petrol :diesel :kerosene :heavy-fuel-oil :lubricating-oil
    :lpg :cng :lng})

(def quantity-min-liters
  "Physical floor for a shipment-record's or delivery-operation's own
  declared quantity (liters, liquid-equivalent for solid/gaseous
  products too for a single uniform unit) -- a real fuel movement is
  never a negative-quantity movement."
  0.0)

(def quantity-max-liters
  "Physical ceiling for a shipment-record's or delivery-operation's own
  declared quantity (liters) -- generous enough to cover a single large
  tanker-truck or short pipeline-batch delivery to a wholesale depot,
  but a reading beyond this is implausible sensor/manifest data, not a
  real movement."
  5000000.0)

(def default-order-value-threshold-usd
  "Supplier-order cost threshold (USD): orders at or above this value
  ALWAYS escalate to a human depot supervisor, regardless of confidence
  -- see README `What this actor does NOT do` and
  `fueldepot.governor/high-value-order-violations`. A representative
  wholesale-scale threshold (a single large bulk-fuel restock order),
  not a per-jurisdiction regulatory figure."
  250000.0)

;; ----------------------------- depot checks -----------------------------

(defn depot-verified?
  "Ground-truth check: has `depot`'s own record been marked verified
  (i.e. its operating license has actually been inspected/confirmed,
  not merely referenced from an unverified delivery/order request)? A
  pure predicate over the depot's own permanent field -- no proposal
  inspection needed."
  [depot]
  (true? (:verified? depot)))

(defn depot-registered?
  "Ground-truth check: does `depot`'s own record carry a `:registered?`
  true flag (i.e. its operating license is on file with the regulator)?
  Scheduling a delivery or coordinating a supplier order against a
  depot that is not on file and registered is the exact scope violation
  this actor's HARD invariant ('depot/license record must be
  independently verified/registered before any action') exists to
  block."
  [depot]
  (true? (:registered? depot)))

(defn depot-ready?
  "Combined ground-truth gate: the depot's own operating-license record
  must be both `verified?` AND `registered?` before ANY delivery
  operation may be scheduled or supplier order coordinated against it.
  Two independent facts on the depot's own permanent record, neither
  inferred from the advisor's own rationale."
  [depot]
  (and (depot-verified? depot) (depot-registered? depot)))

(defn delivery-capacity-exceeded?
  "Ground-truth check for a `:schedule-delivery-operation` proposal:
  would the depot's own recorded `:inventory-liters` + the proposal's
  own claimed `:quantity-liters` exceed the depot's own recorded
  `:tank-capacity-liters`? Needs no proposal inspection or
  stored-verdict lookup -- its inputs are permanent fields already on
  the depot's own record, the same shape
  `adhesivemfg.registry/shipment-weight-exceeded?` (from
  `cloud-itonami-isic-2029`) uses for its own batch-weight recompute.
  Overfilling a fuel storage tank past its own rated capacity is a real
  physical hazard, not merely an accounting overage -- this is why the
  check is a HARD hold, not a soft escalation."
  [depot new-quantity-liters]
  (let [capacity (:tank-capacity-liters depot)
        on-hand (:inventory-liters depot 0.0)]
    (and (number? capacity)
         (number? new-quantity-liters)
         (> (+ (double on-hand) (double new-quantity-liters)) (double capacity)))))

(defn order-value-exceeds-threshold?
  "Ground-truth check for a `:coordinate-supplier-order` proposal:
  INDEPENDENTLY re-derive whether the proposal's own declared
  `:order-value-usd` is at or above `default-order-value-threshold-usd`
  -- never taken on the advisor's self-report `:stake`. Supplier orders
  above this threshold ALWAYS escalate to a human depot supervisor
  (`fueldepot.governor/high-value-order-violations`), regardless of
  confidence."
  ([order-value-usd] (order-value-exceeds-threshold? order-value-usd default-order-value-threshold-usd))
  ([order-value-usd threshold-usd]
   (and (number? order-value-usd)
        (>= (double order-value-usd) (double threshold-usd)))))

(defn fuel-type-valid?
  "Is `fuel-type` one of the closed, known fuel-type values (solid:
  coal/coke; liquid: petrol/diesel/kerosene/heavy-fuel-oil/lubricating-
  oil; gaseous: LPG/CNG/LNG)? nil/blank is treated as invalid (a
  shipment-record patch or delivery-operation must declare a real fuel
  type, not omit it silently)."
  [fuel-type]
  (contains? valid-fuel-types fuel-type))

(defn quantity-valid?
  "Is `quantity-liters` a physically plausible fuel-quantity reading
  (liters)? Rejects nil, non-numbers, negative values, and values
  beyond `quantity-max-liters` -- a fabricated or sensor/manifest-error
  reading, never let through as a real shipment or delivery fact."
  [quantity-liters]
  (and (number? quantity-liters)
       (>= (double quantity-liters) quantity-min-liters)
       (<= (double quantity-liters) quantity-max-liters)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human depot supervisor's/procurement approver's act, not this
  actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-delivery-operation
  "Validate + construct the DELIVERY-OPERATION-SCHEDULE DRAFT -- a
  proposed tanker-truck/pipeline delivery operation against a verified,
  registered depot. Pure function -- does not actually dispatch a
  tanker truck, open a pipeline valve, or execute any delivery; it
  builds the RECORD a depot coordinator would keep.
  `fueldepot.governor` independently re-verifies the depot's own
  verified/registered ground truth and independently recomputes the
  depot's own tank-capacity headroom before this is ever allowed to
  commit."
  [delivery-id depot-id sequence]
  (when-not (and delivery-id (not= delivery-id ""))
    (throw (ex-info "delivery-operation: delivery_id required" {})))
  (when-not (and depot-id (not= depot-id ""))
    (throw (ex-info "delivery-operation: depot_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "delivery-operation: sequence must be >= 0" {})))
  (let [delivery-number (str "DLV-" (zero-pad sequence 6))
        record {"record_id" delivery-number
                "kind" "delivery-operation-schedule-draft"
                "delivery_id" delivery-id
                "depot_id" depot-id
                "immutable" true}]
    {"record" record "delivery_number" delivery-number
     "certificate" (unsigned-certificate "DeliveryOperationSchedule" delivery-number delivery-number)}))

(defn register-supplier-order
  "Validate + construct the SUPPLIER-ORDER-COORDINATION DRAFT -- a
  proposed inbound fuel-procurement order against a verified,
  registered depot. Pure function -- does not place any real purchase
  order or move any real money; it builds the RECORD a depot
  coordinator would keep. `fueldepot.governor` independently
  re-verifies the order's own claimed value against
  `order-value-exceeds-threshold?`, before this is ever allowed to
  commit (escalating rather than blocking, since a large order is a
  legitimate business act that a human procurement approver may
  approve)."
  [order-id depot-id sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "supplier-order: order_id required" {})))
  (when-not (and depot-id (not= depot-id ""))
    (throw (ex-info "supplier-order: depot_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "supplier-order: sequence must be >= 0" {})))
  (let [order-number (str "ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "supplier-order-coordination-draft"
                "order_id" order-id
                "depot_id" depot-id
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "SupplierOrderCoordination" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
