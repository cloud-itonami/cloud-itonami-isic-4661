(ns fueldepot.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `fueldepot.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [fueldepot.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/depot s "depot-001"))))
    (is (true? (:registered? (store/depot s "depot-001"))))
    (is (true? (:verified? (store/depot s "depot-002"))))
    (is (true? (:registered? (store/depot s "depot-002"))))
    (is (false? (:verified? (store/depot s "depot-003"))))
    (is (false? (:registered? (store/depot s "depot-003"))))
    (is (= ["depot-001" "depot-002" "depot-003"] (mapv :id (store/all-depots s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/delivery-history s)))
    (is (= [] (store/order-history s)))
    (is (= [] (store/safety-concerns s)))
    (is (zero? (store/next-delivery-sequence s)))
    (is (zero? (store/next-order-sequence s)))
    (is (false? (store/delivery-already-scheduled? s "dlv-1")))
    (is (false? (store/order-already-coordinated? s "ord-1")))
    (is (nil? (store/delivery-operation s "dlv-1")))
    (is (nil? (store/supplier-order s "ord-1")))))

(deftest fresh-store-has-no-depots
  (let [s (store/mem-store)]
    (is (= [] (store/all-depots s)))
    (is (nil? (store/depot s "depot-001")))))

(deftest shipment-record-log-upserts-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :shipment-record/log :path ["ship-001"]
                             :value {:fuel-type :diesel :quantity-liters 20000.0}})
    (is (= :diesel (:fuel-type (store/shipment-record s "ship-001"))))
    (store/commit-record! s {:effect :shipment-record/log :path ["ship-001"]
                             :value {:direction :receipt}})
    (is (= :diesel (:fuel-type (store/shipment-record s "ship-001"))) "unrelated field preserved")
    (is (= :receipt (:direction (store/shipment-record s "ship-001"))))))

(deftest delivery-operation-schedule-commits-advances-sequence-and-depot-inventory
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :delivery-operation/schedule :path ["dlv-1"]
                               :value {:depot-id "depot-001" :mode :tanker-truck
                                       :quantity-liters 50000.0}})
      (is (= "DLV-000000" (get (first (store/delivery-history s)) "record_id")))
      (is (= "delivery-operation-schedule-draft" (get (first (store/delivery-history s)) "kind")))
      (is (true? (:scheduled? (store/delivery-operation s "dlv-1"))))
      (is (= "depot-001" (:depot-id (store/delivery-operation s "dlv-1"))))
      (is (= 1 (count (store/delivery-history s))))
      (is (= 1 (store/next-delivery-sequence s)))
      (is (true? (store/delivery-already-scheduled? s "dlv-1")))
      (is (= "DLV-000000" (:delivery-number (store/delivery-operation s "dlv-1"))))
      (is (= 1050000.0 (:inventory-liters (store/depot s "depot-001")))
          "1000000.0 seeded + 50000.0 committed"))))

(deftest safety-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-1"]
                             :value {:depot-id "depot-001" :severity :moderate}})
    (is (= 1 (count (store/safety-concerns s))))
    (is (= :moderate (:severity (first (store/safety-concerns s)))))
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-2"]
                             :value {:depot-id "depot-002" :severity :high}})
    (is (= 2 (count (store/safety-concerns s))) "append-only")))

(deftest supplier-order-coordinate-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :supplier-order/coordinate :path ["ord-1"]
                             :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                     :order-value-usd 40000.0}})
    (is (= "ORD-000000" (get (first (store/order-history s)) "record_id")))
    (is (= "supplier-order-coordination-draft" (get (first (store/order-history s)) "kind")))
    (is (= 1 (count (store/order-history s))))
    (is (= 1 (store/next-order-sequence s)))
    (is (= "ORD-000000" (:order-number (store/supplier-order s "ord-1"))))
    (is (true? (store/order-already-coordinated? s "ord-1")))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
