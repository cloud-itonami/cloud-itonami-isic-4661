(ns fueldepot.registry-test
  (:require [clojure.test :refer [deftest is]]
            [fueldepot.registry :as r]))

;; ----------------------------- depot-verified? / depot-registered? / depot-ready? -----------------------------

(deftest depot-is-verified-when-flagged
  (is (true? (r/depot-verified? {:id "d1" :verified? true}))))

(deftest depot-is-not-verified-when-false-or-missing
  (is (false? (r/depot-verified? {:id "d1" :verified? false})))
  (is (false? (r/depot-verified? {:id "d1"}))))

(deftest depot-is-registered-when-flagged
  (is (true? (r/depot-registered? {:registered? true}))))

(deftest depot-is-not-registered-when-false-or-missing
  (is (false? (r/depot-registered? {:registered? false})))
  (is (false? (r/depot-registered? {}))))

(deftest depot-ready-requires-both
  (is (true? (r/depot-ready? {:verified? true :registered? true})))
  (is (false? (r/depot-ready? {:verified? true :registered? false})))
  (is (false? (r/depot-ready? {:verified? false :registered? true})))
  (is (false? (r/depot-ready? {}))))

;; ----------------------------- delivery-capacity-exceeded? -----------------------------

(deftest small-delivery-within-capacity-does-not-exceed
  (is (false? (r/delivery-capacity-exceeded?
               {:tank-capacity-liters 5000000.0 :inventory-liters 1000000.0} 50000.0))))

(deftest delivery-that-pushes-past-capacity-exceeds
  (is (true? (r/delivery-capacity-exceeded?
              {:tank-capacity-liters 8000000.0 :inventory-liters 7500000.0} 600000.0))))

(deftest delivery-exactly-at-capacity-does-not-exceed
  (is (false? (r/delivery-capacity-exceeded?
               {:tank-capacity-liters 8000000.0 :inventory-liters 7500000.0} 500000.0))
      "exactly at capacity is not over, only strictly beyond"))

(deftest missing-capacity-is-not-flagged-exceeded
  (is (false? (r/delivery-capacity-exceeded? {} 100.0)))
  (is (false? (r/delivery-capacity-exceeded? {:tank-capacity-liters 800.0} nil))))

;; ----------------------------- order-value-exceeds-threshold? -----------------------------

(deftest order-value-below-threshold-does-not-exceed
  (is (false? (r/order-value-exceeds-threshold? 40000.0))))

(deftest order-value-at-threshold-exceeds
  (is (true? (r/order-value-exceeds-threshold? 250000.0))
      "at-or-above the threshold escalates"))

(deftest order-value-above-threshold-exceeds
  (is (true? (r/order-value-exceeds-threshold? 300000.0))))

(deftest custom-threshold-is-respected
  (is (true? (r/order-value-exceeds-threshold? 5000.0 4000.0)))
  (is (false? (r/order-value-exceeds-threshold? 3000.0 4000.0))))

(deftest missing-order-value-does-not-exceed
  (is (false? (r/order-value-exceeds-threshold? nil))))

;; ----------------------------- fuel-type-valid? -----------------------------

(deftest known-fuel-types-are-valid
  (doseq [ft [:coal :coke :petrol :diesel :kerosene :heavy-fuel-oil
              :lubricating-oil :lpg :cng :lng]]
    (is (r/fuel-type-valid? ft))))

(deftest fabricated-fuel-type-is-invalid
  (is (not (r/fuel-type-valid? :unobtainium-fuel)))
  (is (not (r/fuel-type-valid? nil))))

;; ----------------------------- quantity-valid? -----------------------------

(deftest typical-quantity-is-valid
  (is (r/quantity-valid? 20000.0))
  (is (r/quantity-valid? 0.0))
  (is (r/quantity-valid? 5000000.0)))

(deftest negative-quantity-is-invalid
  (is (not (r/quantity-valid? -500.0))))

(deftest excessive-quantity-is-invalid
  (is (not (r/quantity-valid? 6000000.0)))
  (is (not (r/quantity-valid? 5000000.01))))

(deftest non-numeric-or-missing-quantity-is-invalid
  (is (not (r/quantity-valid? nil)))
  (is (not (r/quantity-valid? "20000"))))

;; ----------------------------- register-delivery-operation -----------------------------

(deftest delivery-operation-is-a-draft-not-a-real-dispatch
  (let [result (r/register-delivery-operation "dlv-1" "depot-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest delivery-operation-assigns-delivery-number
  (let [result (r/register-delivery-operation "dlv-1" "depot-001" 7)]
    (is (= (get result "delivery_number") "DLV-000007"))
    (is (= (get-in result ["record" "delivery_id"]) "dlv-1"))
    (is (= (get-in result ["record" "depot_id"]) "depot-001"))
    (is (= (get-in result ["record" "kind"]) "delivery-operation-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest delivery-operation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-delivery-operation "" "depot-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-delivery-operation "dlv-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-delivery-operation "dlv-1" "depot-001" -1))))

;; ----------------------------- register-supplier-order -----------------------------

(deftest supplier-order-is-a-draft-not-a-real-purchase
  (let [result (r/register-supplier-order "ord-1" "depot-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest supplier-order-assigns-order-number
  (let [result (r/register-supplier-order "ord-1" "depot-001" 7)]
    (is (= (get result "order_number") "ORD-000007"))
    (is (= (get-in result ["record" "order_id"]) "ord-1"))
    (is (= (get-in result ["record" "depot_id"]) "depot-001"))
    (is (= (get-in result ["record" "kind"]) "supplier-order-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest supplier-order-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supplier-order "" "depot-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supplier-order "ord-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-supplier-order "ord-1" "depot-001" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-delivery-operation "dlv-1" "depot-001" 0)
        hist (r/append [] c1)
        c2 (r/register-delivery-operation "dlv-2" "depot-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "DLV-000000" (get-in hist2 [0 "record_id"])))
    (is (= "DLV-000001" (get-in hist2 [1 "record_id"])))))
