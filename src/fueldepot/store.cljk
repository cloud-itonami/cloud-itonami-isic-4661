(ns fueldepot.store
  "SSoT for the ISIC 4661 (wholesale of solid, liquid and gaseous fuels
  and related products) fuel-wholesale-depot operations coordination
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  Scope note: like its closest structural analog
  (`cloud-itonami-isic-2029`'s own `adhesivemfg.store`), this build
  ships a single `MemStore` backend only (atom of EDN) -- the
  deterministic default for dev/tests/demo, no deps. This vertical is
  self-contained (no external fuel-depot-operations capability
  library, no jurisdiction-scoped Datomic-parity requirement driving a
  second backend); a `langchain.db`-backed store can be added later
  behind the same protocol without changing any caller.

  Four kinds of entity live here:
    - `depots`            -- the central entity. A fuel-wholesale
                              depot's own operating-license/tank-
                              capacity/inventory record.
                              `:verified?` marks whether the depot's
                              own operating license has actually been
                              inspected/confirmed (never inferred from
                              a routine shipment-record patch);
                              `:registered?` marks whether it is on
                              file with the regulator;
                              `:inventory-liters` tracks the depot's
                              own current-on-hand ground truth.
    - `shipment-records`  -- fuel-receipt/dispatch/inventory-level
                              administrative log entries
                              (`:log-shipment-record`), keyed by their
                              own subject id -- upserted directly, the
                              same shape `adhesivemfg.store`'s own
                              `batches` map uses for
                              `:log-production-batch`.
    - `delivery-operations` -- a scheduled tanker-truck/pipeline
                              delivery-operation DRAFT against a depot
                              (`fueldepot.registry`'s
                              `register-delivery-operation`). Dedicated
                              `:scheduled?` double-schedule guard
                              (never a `:status` value -- the same
                              discipline every prior governor's guards
                              establish).
    - `supplier-orders`   -- a proposed inbound fuel-procurement order
                              DRAFT against a depot
                              (`fueldepot.registry`'s
                              `register-supplier-order`). Dedicated
                              `:ordered?` double-order guard.

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which shipment was logged, which
  delivery operation was scheduled against a verified/registered
  depot, which supplier order was coordinated and at what
  independently-recomputed value, approved by whom, which safety
  concern was flagged' is always a query over an immutable log -- the
  audit trail a depot owner or downstream regulator trusting this
  coordinator needs."
  (:require [fueldepot.registry :as registry]))

(defprotocol Store
  (depot [s id])
  (all-depots [s])
  (shipment-record [s id])
  (all-shipment-records [s])
  (delivery-operation [s id])
  (all-delivery-operations [s])
  (supplier-order [s id])
  (safety-concerns [s] "the append-only safety-concern log")
  (ledger [s])
  (delivery-history [s] "the append-only delivery-operation-schedule history (fueldepot.registry drafts)")
  (order-history [s] "the append-only supplier-order-coordination history (fueldepot.registry drafts)")
  (next-delivery-sequence [s] "next delivery-number sequence")
  (next-order-sequence [s] "next order-number sequence")
  (delivery-already-scheduled? [s delivery-id] "has this delivery operation already been scheduled?")
  (order-already-coordinated? [s order-id] "has this supplier order already been coordinated?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-depots [s depots] "replace/seed the depot directory (map id->depot)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-depots []
  {"depot-001" {:id "depot-001" :name "Bayside Fuel Terminal"
                :license-number "MOF-FUEL-88213"
                :fuel-types #{:diesel :petrol :kerosene}
                :tank-capacity-liters 5000000.0 :inventory-liters 1000000.0
                :verified? true :registered? true
                :last-inspection-date "2026-06-01"}
   "depot-002" {:id "depot-002" :name "Harborline Bulk Storage"
                :license-number "MOF-FUEL-88214"
                :fuel-types #{:heavy-fuel-oil :lubricating-oil}
                :tank-capacity-liters 8000000.0 :inventory-liters 7500000.0
                :verified? true :registered? true
                :last-inspection-date "2026-06-01"}
   "depot-003" {:id "depot-003" :name "Northgate LPG Depot"
                :license-number nil
                :fuel-types #{:lpg}
                :tank-capacity-liters 2000000.0 :inventory-liters 0.0
                :verified? false :registered? false
                :last-inspection-date "2026-05-15"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-delivery-operation!
  "Backend-agnostic `:delivery-operation/schedule` -- drafts the
  delivery-operation-schedule record via `fueldepot.registry` and
  returns {:result .. :patch ..} for the caller to persist."
  [s delivery-id depot-id]
  (let [seq-n (next-delivery-sequence s)
        result (registry/register-delivery-operation delivery-id depot-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :delivery-number (get result "delivery_number")}}))

(defn- coordinate-supplier-order!
  "Backend-agnostic `:supplier-order/coordinate` -- drafts the
  supplier-order-coordination record via `fueldepot.registry` and
  returns {:result .. :patch ..} for the caller to persist."
  [s order-id depot-id]
  (let [seq-n (next-order-sequence s)
        result (registry/register-supplier-order order-id depot-id seq-n)]
    {:result result
     :patch {:ordered? true
             :order-number (get result "order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (depot [_ id] (get-in @a [:depots id]))
  (all-depots [_] (sort-by :id (vals (:depots @a))))
  (shipment-record [_ id] (get-in @a [:shipment-records id]))
  (all-shipment-records [_] (sort-by :id (vals (:shipment-records @a))))
  (delivery-operation [_ id] (get-in @a [:delivery-operations id]))
  (all-delivery-operations [_] (sort-by :id (vals (:delivery-operations @a))))
  (supplier-order [_ id] (get-in @a [:supplier-orders id]))
  (safety-concerns [_] (:safety-concerns @a))
  (ledger [_] (:ledger @a))
  (delivery-history [_] (:delivery-history @a))
  (order-history [_] (:order-history @a))
  (next-delivery-sequence [_] (:delivery-sequence @a 0))
  (next-order-sequence [_] (:order-sequence @a 0))
  (delivery-already-scheduled? [_ delivery-id]
    (boolean (get-in @a [:delivery-operations delivery-id :scheduled?])))
  (order-already-coordinated? [_ order-id]
    (boolean (get-in @a [:supplier-orders order-id :ordered?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :shipment-record/log)
      (swap! a update-in [:shipment-records (first path)] merge (assoc value :id (first path)))

      (= effect :delivery-operation/schedule)
      (let [delivery-id (first path)
            depot-id (:depot-id value)
            {:keys [result patch]} (schedule-delivery-operation! s delivery-id depot-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :delivery-sequence (fnil inc 0))
                       (update-in [:delivery-operations delivery-id] merge (assoc value :id delivery-id) patch)
                       (update :delivery-history registry/append result)
                       (update-in [:depots depot-id :inventory-liters]
                                  (fn [prev]
                                    (+ (double (or prev 0.0))
                                       (double (or (:quantity-liters value) 0.0))))))))
        result)

      (= effect :safety-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :safety-concerns conj concern)
        concern)

      (= effect :supplier-order/coordinate)
      (let [order-id (first path)
            depot-id (:depot-id value)
            {:keys [result patch]} (coordinate-supplier-order! s order-id depot-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :order-sequence (fnil inc 0))
                       (update-in [:supplier-orders order-id] merge (assoc value :id order-id) patch)
                       (update :order-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-depots [s depots] (when (seq depots) (swap! a assoc :depots depots)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:depots {} :shipment-records {} :delivery-operations {} :supplier-orders {}
                      :records {} :safety-concerns []
                      :ledger [] :delivery-sequence 0 :delivery-history []
                      :order-sequence 0 :order-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained depot set -- two
  verified+registered depots (one with delivery headroom, one nearly
  full so a small new delivery blows through its own logged tank
  capacity -- HARD hold), one UNVERIFIED/unregistered depot (blocks any
  delivery scheduling or supplier-order coordination against it) -- so
  the actor + demo + tests run offline. Returns `s` (thread-friendly
  with `->`)."
  [s]
  (with-depots s (sample-depots))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
