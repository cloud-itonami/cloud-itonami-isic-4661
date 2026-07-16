(ns fueldepot.operation-test
  "Smoke tests for the compiled FuelDepotOperationActor graph itself
  (build + one happy path per op). The governor's full rule contract
  (HARD holds, escalation, phase gating) is exercised in
  `fueldepot.governor-contract-test`; the Store contract in
  `fueldepot.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [fueldepot.operation :as op]
            [fueldepot.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :depot-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "FuelDepotOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-shipment-record-logging-proposal
  (testing "Proposing a shipment-record log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-shipment-record :effect :propose :subject "ship-001"
                           :patch {:fuel-type :diesel :quantity-liters 20000.0}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-delivery-operation-scheduling
  (testing "Delivery-operation scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                           :value {:depot-id "depot-001" :mode :tanker-truck
                                   :quantity-liters 50000.0 :scheduled-date "2026-08-01"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-safety-concern-escalation
  (testing "Safety concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:depot-id "depot-001" :severity :moderate :description "軽度な燃料臭を検知"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-supplier-order-coordination-proposal
  (testing "Supplier-order coordination proposal is submitted and (when clean, below threshold) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                           :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                   :order-value-usd 40000.0}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
