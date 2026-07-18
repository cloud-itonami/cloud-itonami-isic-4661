(ns fueldepot.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout). This repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`fueldepot.operation` -> `fueldepot.governor` -> `fueldepot.store`)
  through a scenario mined directly from this repo's own `fueldepot.sim`
  demo driver (`clojure -M:dev:run`, confirmed to run correctly against
  the real seeded depot directory before this file was written -- every
  id it drives (`depot-001`/`depot-002`/`depot-003`, `ship-001`,
  `dlv-1..4`, `concern-1`, `ord-1`) exists in `fueldepot.store/sample-
  depots`, and every disposition it produces matches the Fuel Depot
  Operations Governor's own documented rules exactly, unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, which turned out to
  reference ids absent from its own seed data -- so it was safe to
  reuse rather than author from scratch), rendered deterministically --
  no invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [fueldepot.store :as store]
            [fueldepot.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :depot-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

;; Single source of truth for both `run-demo!` (execution) and `render`
;; (the Operations table's Depot column) -- every (subject, op, depot-id)
;; triple here is exactly what gets executed below, so the rendered
;; table can never drift from what actually ran. Mined directly from
;; `fueldepot.sim`'s own verified-correct scenario (ids re-confirmed
;; against `store/sample-depots` before this file was written):
;;   depot-001 -- verified, registered, 5,000,000L capacity / 1,000,000L
;;                on hand (headroom for a 50,000L delivery).
;;   depot-002 -- verified, registered, 8,000,000L capacity / 7,500,000L
;;                on hand (near-full -- a 600,000L delivery blows past
;;                capacity, the ground-truth recompute this governor
;;                exists to catch).
;;   depot-003 -- UNVERIFIED, unregistered (its own operating license
;;                has never been inspected/filed) -- blocks any delivery
;;                scheduling or supplier-order coordination against it.
(def ^:private scenario-requests
  [{:subject "ship-001" :op :log-shipment-record        :depot-id nil}
   {:subject "dlv-1"    :op :schedule-delivery-operation :depot-id "depot-001"}
   {:subject "concern-1" :op :flag-safety-concern         :depot-id "depot-001"}
   {:subject "ord-1"    :op :coordinate-supplier-order   :depot-id "depot-001"}
   {:subject "dlv-2"    :op :schedule-delivery-operation :depot-id "depot-003"}
   {:subject "dlv-3"    :op :schedule-delivery-operation :depot-id "depot-002"}
   {:subject "dlv-4"    :op :schedule-delivery-operation :depot-id "depot-001"}])

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: ship-001's clean shipment-record log clears at
  phase-3 auto-commit (the ONLY op this actor ever auto-commits, per
  `fueldepot.phase`'s phase-3 `:auto` set); dlv-1 (a 50,000L delivery
  into depot-001, well within its logged 5,000,000L tank capacity),
  concern-1 (a safety-concern flag on depot-001 -- ALWAYS escalates,
  `fueldepot.governor/high-stakes`'s `:coordination/safety-concern`,
  regardless of confidence) and ord-1 (a 40,000 USD supplier order into
  depot-001, below the 250,000 USD escalation threshold but still never
  auto-eligible at any phase) each escalate and are approved by a human
  depot coordinator; dlv-2 (a delivery into depot-003, whose own record
  is UNVERIFIED/unregistered) HARD-holds on `:depot-not-verified`,
  independently re-derived from depot-003's own permanent fields, never
  reaching a human; dlv-3 (a 600,000L delivery into depot-002, whose own
  logged 7,500,000L on-hand + this request would exceed its own
  8,000,000L tank capacity) HARD-holds on `:delivery-capacity-exceeded`,
  independently recomputed from depot-002's own permanent fields; dlv-4
  (a delivery into depot-001 whose own `:value` declares
  `:finalize-tank-integrity-clearance? true`) HARD-holds on
  `:tank-integrity-clearance-finalize-blocked` -- permanent, no phase or
  human approval can ever override this. Three distinct HARD-hold
  reasons, none reaching a human. Returns the resulting store -- every
  field read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (exec! actor "ship-001" {:op :log-shipment-record :effect :propose :subject "ship-001"
                              :patch {:fuel-type :diesel :quantity-liters 20000.0 :direction :receipt}})

    (exec! actor "dlv-1" {:op :schedule-delivery-operation :effect :propose :subject "dlv-1"
                           :value {:depot-id "depot-001" :mode :tanker-truck
                                   :quantity-liters 50000.0 :scheduled-date "2026-08-01"}})
    (approve! actor "dlv-1")

    (exec! actor "concern-1" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                               :value {:depot-id "depot-001" :severity :moderate
                                       :description "貯蔵タンク周辺での軽度な燃料臭を検知"}})
    (approve! actor "concern-1")

    (exec! actor "ord-1" {:op :coordinate-supplier-order :effect :propose :subject "ord-1"
                           :value {:depot-id "depot-001" :supplier "Meridian Fuel Supply Co."
                                   :order-value-usd 40000.0}})
    (approve! actor "ord-1")

    (exec! actor "dlv-2" {:op :schedule-delivery-operation :effect :propose :subject "dlv-2"
                           :value {:depot-id "depot-003" :mode :pipeline
                                   :quantity-liters 10000.0 :scheduled-date "2026-08-01"}})

    (exec! actor "dlv-3" {:op :schedule-delivery-operation :effect :propose :subject "dlv-3"
                           :value {:depot-id "depot-002" :mode :tanker-truck
                                   :quantity-liters 600000.0 :scheduled-date "2026-08-01"}})

    (exec! actor "dlv-4" {:op :schedule-delivery-operation :effect :propose :subject "dlv-4"
                           :value {:depot-id "depot-001" :mode :pipeline
                                   :quantity-liters 1000.0 :scheduled-date "2026-09-01"
                                   :finalize-tank-integrity-clearance? true}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- depot-row [{:keys [id name license-number fuel-types
                          tank-capacity-liters inventory-liters
                          verified? registered?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc name)
          (if license-number (esc license-number) "<span class=\"muted\">none on file</span>")
          (esc (str/join ", " (sort (map clojure.core/name fuel-types))))
          (esc tank-capacity-liters) (esc inventory-liters)
          (str (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
               " &middot; "
               (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>"))))

(defn- op-row [ledger {:keys [subject op depot-id]}]
  (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc subject) (esc (name op))
          (if depot-id (esc depot-id) "<span class=\"muted\">n/a</span>")
          (status-cell ledger subject)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map clojure.core/name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `What
  ;; this actor does`, `fueldepot.governor`/`fueldepot.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-shipment-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean -- the ONLY op this actor ever auto-commits</span> &middot; HARD holds: fabricated <code>:fuel-type</code>, implausible <code>:quantity-liters</code></td></tr>"
   "        <tr><td><code>:schedule-delivery-operation</code></td><td><span class=\"warn\">ALWAYS human approval, never auto at any phase</span> &middot; HARD holds: depot not verified/registered, tank capacity independently recomputed as exceeded, double-schedule of the same delivery, tank-integrity-clearance finalize attempt (permanent, no override)</td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS escalates regardless of confidence (<code>fueldepot.governor/high-stakes</code>)</span> &middot; never gated on depot verification -- a concern may be raised about ANY depot</td></tr>"
   "        <tr><td><code>:coordinate-supplier-order</code></td><td><span class=\"warn\">ALWAYS human approval, never auto at any phase</span> &middot; HARD holds: depot not verified/registered, double-order of the same supplier order &middot; order value &ge; $250,000 USD independently re-derived to always escalate regardless of confidence</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        depots (store/all-depots db)
        depot-rows (str/join "\n" (map depot-row depots))
        op-rows (str/join "\n" (map (partial op-row ledger) scenario-requests))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4661 &middot; fuel wholesale depot operations</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Fuel wholesale depot operations (ISIC 4661) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · tank/pipeline-integrity and hazmat-storage-safety clearance finalize permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Depots</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>fueldepot.store</code> via <code>fueldepot.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Depot</th><th>Name</th><th>License</th><th>Fuel types</th><th>Tank capacity (L)</th><th>Inventory (L)</th><th>License status</th></tr></thead>\n"
     "      <tbody>\n"
     depot-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Operations (this run)</h2>\n"
     "    <p class=\"muted\">Every coordination request this scenario drove through the actor, and the depot (if any) its own proposal referenced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Subject</th><th>Op</th><th>Depot</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     op-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Fuel Depot Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Depot verification/registration, tank-capacity headroom, fuel-type validity and quantity plausibility are all independently checked from the depot's/request's own permanent fields, never trusted from the advisor's proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/delivery-history db)) "draft delivery-operation records,"
             (count (store/order-history db)) "draft supplier-order records )")))
