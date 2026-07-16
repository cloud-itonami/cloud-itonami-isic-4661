(ns fueldepot.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT control tank/pipeline equipment directly...
  does NOT finalize a tank/pipeline-integrity clearance or a
  hazmat-storage-safety clearance') implemented faithfully. The single
  invariant under test:

    FuelDepotAdvisor never schedules a delivery operation, flags a
    safety concern, or coordinates a supplier order the Fuel Depot
    Operations Governor would reject; `:schedule-delivery-operation`/
    `:flag-safety-concern`/`:coordinate-supplier-order` NEVER
    auto-commit at any phase; `:log-shipment-record` (no
    physical/financial risk) MAY auto-commit when clean; and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [fueldepot.advisor :as advisor]
            [fueldepot.governor :as governor]
            [fueldepot.store :as store]
            [fueldepot.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :depot-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-shipment-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-shipment-record :effect :propose :subject "ship-001"
                   :patch {:fuel-type :diesel :quantity-liters 20000.0}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :diesel (:fuel-type (store/shipment-record db "ship-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-delivery-operation-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                     :value {:depot-id "depot-001" :mode :tanker-truck
                             :quantity-liters 50000.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/delivery-operation db "dlv-1"))))
        (is (= 1 (count (store/delivery-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-shipment-record :effect :direct-write :subject "ship-001"
                     :patch {:fuel-type :diesel}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :open-pipeline-valve :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest depot-not-verified-is-held-and-unoverridable-for-delivery
  (testing "scheduling against an unverified/unregistered depot -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-delivery-operation :effect :propose :subject "dlv-2"
                     :value {:depot-id "depot-003" :mode :pipeline
                             :quantity-liters 10000.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:depot-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/delivery-history db))))))

(deftest depot-not-verified-is-held-and-unoverridable-for-order
  (testing "coordinating a supplier order against an unverified/unregistered depot -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :coordinate-supplier-order :effect :propose :subject "ord-2"
                     :value {:depot-id "depot-003" :supplier "Northgate Bulk Gas"
                             :order-value-usd 10000.0}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:depot-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/order-history db))))))

(deftest delivery-capacity-exceeded-is-held-and-unoverridable
  (testing "a delivery proposal whose quantity would exceed the depot's own logged tank capacity -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-delivery-operation :effect :propose :subject "dlv-3"
                     :value {:depot-id "depot-002" :mode :tanker-truck
                             :quantity-liters 600000.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:delivery-capacity-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/delivery-history db))))))

(deftest tank-integrity-clearance-finalize-is-held-and-permanently-blocked
  (testing "a proposal that sets :finalize-tank-integrity-clearance? true -> HOLD, PERMANENT, never reaches request-approval even though the depot is verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-delivery-operation :effect :propose :subject "dlv-4"
                     :value {:depot-id "depot-001" :mode :pipeline
                             :quantity-liters 1000.0 :scheduled-date "2026-09-01"
                             :finalize-tank-integrity-clearance? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:tank-integrity-clearance-finalize-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/delivery-history db))))))

(deftest hazmat-storage-safety-clearance-finalize-is-held-and-permanently-blocked
  (testing "a proposal that sets :finalize-hazmat-storage-safety-clearance? true -> HOLD, PERMANENT, never reaches request-approval -- deciding a hazmat-storage-safety clearance is exclusively a certification authority's call"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-shipment-record :effect :propose :subject "ship-001"
                     :patch {:finalize-hazmat-storage-safety-clearance? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:hazmat-storage-safety-clearance-finalize-blocked} (-> (store/ledger db) last :basis)))
      (is (not (:finalize-hazmat-storage-safety-clearance? (store/shipment-record db "ship-001")))
          "the clearance-finalize attempt never lands in the SSoT"))))

(deftest schedule-delivery-operation-double-schedule-is-held
  (testing "scheduling the SAME delivery operation twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                                  :value {:depot-id "depot-001" :mode :tanker-truck
                                          :quantity-liters 50000.0 :scheduled-date "2026-08-01"}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                                   :value {:depot-id "depot-001" :mode :tanker-truck
                                           :quantity-liters 50000.0 :scheduled-date "2026-08-01"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/delivery-history db))) "still only the one earlier schedule"))))

(deftest coordinate-supplier-order-double-order-is-held
  (testing "coordinating the SAME supplier order twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9c" {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                                  :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                          :order-value-usd 40000.0}} coordinator)
          _ (approve! actor "t9c")
          res (exec-op actor "t9d" {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                                    :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                            :order-value-usd 40000.0}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-ordered} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/order-history db))) "still only the one earlier order"))))

(deftest invalid-fuel-type-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-shipment-record :effect :propose :subject "ship-001"
                                  :patch {:fuel-type :unobtainium-fuel}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-fuel-type} (-> (store/ledger db) last :basis)))
    (is (not= :unobtainium-fuel (:fuel-type (store/shipment-record db "ship-001"))) "fabricated fuel-type never lands in the SSoT")))

(deftest invalid-quantity-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-shipment-record :effect :propose :subject "ship-001"
                                  :patch {:quantity-liters -500.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-quantity} (-> (store/ledger db) last :basis)))
    (is (not= -500.0 (:quantity-liters (store/shipment-record db "ship-001"))) "fabricated quantity reading never lands in the SSoT")))

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:depot-id "depot-001" :severity :moderate
                                            :description "storage-tank area minor fuel odor detected"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:depot-id "depot-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest coordinate-supplier-order-always-needs-approval
  (testing "a CLEAN, below-threshold supplier-order coordination is never auto-eligible -- always escalates"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                                    :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                            :order-value-usd 40000.0}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/order-history db))))))))

(deftest high-value-supplier-order-escalates-even-if-advisor-reported-high-confidence
  (testing "an order-value at/above the threshold escalates via the governor's own independent recompute, regardless of what the advisor's own confidence says"
    (let [[_db actor] (fresh)
          res (exec-op actor "t15" {:op :coordinate-supplier-order :effect :propose :subject "ord-3"
                                    :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                            :order-value-usd 300000.0}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t15")]
        (is (= :commit (get-in r2 [:state :disposition])))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-shipment-record :effect :propose :subject "ship-001"
                          :patch {:fuel-type :diesel}} coordinator)
      (exec-op actor "b" {:op :log-shipment-record :effect :propose :subject "ship-001"
                          :patch {:fuel-type :fabricated-fuel}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ----------------------------- self-tripping-bug regression guard -----------------------------
;;
;; A sibling-fleet bug class has recurred where a governor's own
;; scope-exclusion term list was phrased as a bare noun and accidentally
;; matched inside the mock advisor's own DEFAULT rationale/disclaimer
;; text for a legitimate, allowed proposal -- causing the actor to
;; self-block on its own happy path. `fueldepot.governor`'s two
;; permanent-block checks are boolean-flag-based (never text-search),
;; so this class of bug is structurally impossible here -- but this
;; test asserts that directly, for every op's own default mock-advisor
;; proposal against clean, verified fixture data, so a future refactor
;; toward text-matching would be caught immediately.

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion-checks
  (testing "none of the four ops' own DEFAULT mock-advisor proposals ever trip tank-integrity-clearance-finalize-blocked or hazmat-storage-safety-clearance-finalize-blocked on a legitimate, allowed happy-path request"
    (let [[db _actor] (fresh)
          mock (advisor/mock-advisor)
          requests [{:op :log-shipment-record :effect :propose :subject "ship-001"
                     :patch {:fuel-type :diesel :quantity-liters 20000.0}}
                    {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                     :value {:depot-id "depot-001" :mode :tanker-truck
                             :quantity-liters 50000.0 :scheduled-date "2026-08-01"}}
                    {:op :flag-safety-concern :effect :propose :subject "concern-1"
                     :value {:depot-id "depot-001" :severity :moderate
                             :description "storage-tank area minor fuel odor detected, integrity and safety walk-down scheduled"}}
                    {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                     :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                             :order-value-usd 40000.0}}]]
      (doseq [request requests]
        (let [proposal (advisor/-advise mock db request)
              verdict (governor/check request {:actor-id "coord-1"} proposal db)
              tripped-rules (set (map :rule (:violations verdict)))]
          (is (not (contains? tripped-rules :tank-integrity-clearance-finalize-blocked))
              (str (:op request) ": default advisor rationale/summary must never self-trip the tank-integrity-clearance-finalize block"))
          (is (not (contains? tripped-rules :hazmat-storage-safety-clearance-finalize-blocked))
              (str (:op request) ": default advisor rationale/summary must never self-trip the hazmat-storage-safety-clearance-finalize block")))))))
