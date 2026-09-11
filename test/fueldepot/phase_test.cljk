(ns fueldepot.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-delivery-operation`, `:flag-safety-concern`,
  and `:coordinate-supplier-order` must NEVER be members of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [fueldepot.phase :as phase]))

(deftest schedule-delivery-operation-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real delivery-operation schedule"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-delivery-operation))
          (str "phase " n " must not auto-commit :schedule-delivery-operation")))))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest coordinate-supplier-order-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :coordinate-supplier-order))
        (str "phase " n " must not auto-commit :coordinate-supplier-order"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-shipment-record carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-shipment-record} (:auto (get phase/phases 3))))))

(deftest schedule-delivery-operation-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-delivery-operation))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-delivery-operation)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-delivery-operation))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-shipment-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-delivery-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-supplier-order} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-shipment-record} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-shipment-record} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
