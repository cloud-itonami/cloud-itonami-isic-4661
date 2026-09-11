(ns fueldepot.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean depot through
  intake -> delivery-operation scheduling (escalate/approve) ->
  safety-concern flag (escalate/approve) -> supplier-order coordination
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  delivery operation scheduled against an UNVERIFIED/unregistered
  depot, a supplier order coordinated against an UNVERIFIED/
  unregistered depot, a delivery proposal that would exceed the
  depot's own logged tank capacity, a proposal that tries to FINALIZE a
  tank/pipeline-integrity clearance directly (permanently blocked, no
  override), a proposal that tries to FINALIZE a hazmat-storage-safety
  clearance directly (permanently blocked, no override), a
  double-schedule of the same delivery operation, a double-order of the
  same supplier order, a shipment-record patch with a fabricated fuel
  type, a shipment-record patch with an implausible quantity reading,
  and a high-value supplier order that escalates regardless of
  confidence.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [fueldepot.store :as store]
            [fueldepot.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :depot-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-shipment-record ship-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-shipment-record :effect :propose :subject "ship-001"
                        :patch {:fuel-type :diesel :quantity-liters 20000.0 :direction :receipt}}
                       coordinator))

    (println "== schedule-delivery-operation dlv-1 into depot-001 (verified, registered, within capacity -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                       :value {:depot-id "depot-001" :mode :tanker-truck
                               :quantity-liters 50000.0 :scheduled-date "2026-08-01"}}
                      coordinator)]
      (println r)
      (println "-- human depot supervisor approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on depot-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:depot-id "depot-001" :severity :moderate
                               :description "貯蔵タンク周辺での軽度な燃料臭を検知"}}
                      coordinator)]
      (println r)
      (println "-- human depot supervisor approves --")
      (println (approve! actor "t3")))

    (println "== coordinate-supplier-order ord-1 into depot-001 (verified, registered, below threshold -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                       :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                               :order-value-usd 40000.0}}
                      coordinator)]
      (println r)
      (println "-- human procurement approver approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold / escalation scenarios --\n")

    (println "== log-shipment-record with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-shipment-record :effect :direct-write :subject "ship-001"
                        :patch {:fuel-type :diesel}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :open-pipeline-valve :effect :propose :subject "depot-001"}
                       coordinator))

    (println "== schedule-delivery-operation dlv-2 into depot-003 (UNVERIFIED/unregistered depot -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-delivery-operation :effect :propose :subject "dlv-2"
                        :value {:depot-id "depot-003" :mode :pipeline
                                :quantity-liters 10000.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== coordinate-supplier-order ord-2 into depot-003 (UNVERIFIED/unregistered depot -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :coordinate-supplier-order :effect :propose :subject "ord-2"
                        :value {:depot-id "depot-003" :supplier "Northgate Bulk Gas"
                                :order-value-usd 10000.0}}
                       coordinator))

    (println "== schedule-delivery-operation dlv-3 into depot-002 (600000L would exceed capacity 8000000 vs on-hand 7500000 -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :schedule-delivery-operation :effect :propose :subject "dlv-3"
                        :value {:depot-id "depot-002" :mode :tanker-truck
                                :quantity-liters 600000.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== schedule-delivery-operation dlv-4 with :finalize-tank-integrity-clearance? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-delivery-operation :effect :propose :subject "dlv-4"
                        :value {:depot-id "depot-001" :mode :pipeline
                                :quantity-liters 1000.0 :scheduled-date "2026-09-01"
                                :finalize-tank-integrity-clearance? true}}
                       coordinator))

    (println "== log-shipment-record ship-001 with :finalize-hazmat-storage-safety-clearance? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10b"
                       {:op :log-shipment-record :effect :propose :subject "ship-001"
                        :patch {:finalize-hazmat-storage-safety-clearance? true}}
                       coordinator))

    (println "== schedule-delivery-operation dlv-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                        :value {:depot-id "depot-001" :mode :tanker-truck
                                :quantity-liters 50000.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== coordinate-supplier-order ord-1 AGAIN (double-order -> HARD hold) ==")
    (println (exec-op actor "t11b"
                       {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                        :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                :order-value-usd 40000.0}}
                       coordinator))

    (println "== log-shipment-record ship-001 with a fabricated fuel-type -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-shipment-record :effect :propose :subject "ship-001"
                        :patch {:fuel-type :unobtainium-fuel}}
                       coordinator))

    (println "== log-shipment-record ship-001 with an implausible quantity reading -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-shipment-record :effect :propose :subject "ship-001"
                        :patch {:quantity-liters -500.0}}
                       coordinator))

    (println "== coordinate-supplier-order ord-3 into depot-001 (order-value 300000 USD >= threshold -> escalates regardless of confidence, approve) ==")
    (let [r (exec-op actor "t14"
                      {:op :coordinate-supplier-order :effect :propose :subject "ord-3"
                       :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                               :order-value-usd 300000.0}}
                      coordinator)]
      (println r)
      (println "-- human procurement approver approves --")
      (println (approve! actor "t14")))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft delivery-operation records ==")
    (doseq [r (store/delivery-history db)] (println r))

    (println "\n== draft supplier-order records ==")
    (doseq [r (store/order-history db)] (println r))))
