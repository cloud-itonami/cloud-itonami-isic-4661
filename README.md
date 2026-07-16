# cloud-itonami-isic-4661: Wholesale of solid, liquid and gaseous fuels and related products

Open Business Blueprint for **ISIC Rev.4 4661**: wholesale of solid, liquid and gaseous fuels and related products — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **fuel-wholesale-depot operations**: fuel-receipt/dispatch/inventory-level data logging, tanker-truck/pipeline delivery-operation scheduling, safety-concern flagging, and supplier-procurement-order coordination.

This repository designs a forkable OSS business for fuel-wholesale-depot operations: run by a licensed operator so a depot keeps its own operating records instead of renting a closed SaaS.

## Scope: a depot's own back-office coordination, not fuel trading

ISIC 4661 covers wholesale of solid (coal, coke), liquid (petrol, diesel, kerosene, heavy fuel oil, lubricating oil), and gaseous (LPG, CNG, LNG) fuels and related products. This build models the wholesale **depot's own back-office operations coordination** — a fixed storage-tank facility operating under a license, receiving and dispatching fuel products, scheduling delivery operations, and procuring supplier restocks.

This is distinct from `cloud-itonami-isic-4671` (wholesale of solid, liquid and gaseous fuels, ISIC Rev.5 numbering), which models bulk fuel **trading** — counterparty credit clearance, sanctions screening, and contract-on-file verification ahead of a real delivery dispatch or invoice settlement. This actor is a **depot operations coordinator**, not a trading counterparty-diligence governor: it has no notion of counterparty credit or sanctions screening, and its own permanent scope boundaries are physical (tank capacity, tank/pipeline integrity) and regulatory (hazmat-storage-safety clearance) rather than financial/counterparty.

## What this actor does

Proposes **fuel-wholesale-depot operations coordination**, not tank/pipeline control or hazmat-clearance decision-making:
- `:log-shipment-record` — fuel-receipt/dispatch/inventory-level data logging (administrative, not an operational decision)
- `:schedule-delivery-operation` — tanker-truck/pipeline delivery-operation scheduling proposal
- `:flag-safety-concern` — surface a leak/spill/tank-integrity concern (always escalates)
- `:coordinate-supplier-order` — supplier fuel-procurement order coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a hazmat-handling and storage-safety-critical, regulated domain**
(tank/pipeline equipment, leak/spill/tank-integrity hazard, hazmat-storage-safety certification authority):

- Does NOT control tank/pipeline equipment directly (no valve actuation, no pump control)
- Does NOT make depot-safety or storage-safety decisions (that's the depot supervisor's exclusive human authority)
- Does NOT finalize a tank/pipeline-integrity clearance — this is a PERMANENT, unconditional block; no phase and no human approval can ever override it
- Does NOT finalize a hazmat-storage-safety clearance — EXCLUSIVELY a safety-certification authority's call, never this actor's; also a PERMANENT, unconditional block
- ONLY proposes/coordinates operations back-office; all tank/pipeline actuation and all hazmat-storage-safety/tank-integrity clearance decisions require the appropriate human or certification authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation
- Supplier orders above a cost threshold ALWAYS escalate, regardless of the advisor's own confidence

## Architecture

Classic governed-actor pattern (`fueldepot.operation/build`, a langgraph-clj StateGraph):
1. **`fueldepot.advisor`** (sealed intelligence node, `FuelDepotAdvisor`): proposes decisions only, never commits
2. **`fueldepot.governor`** (independent, `Fuel Depot Operations Governor`): validates against domain rules, re-derived from `fueldepot.registry`'s pure functions and `fueldepot.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Depot/license record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (delivery-operation scheduling and supplier-order coordination both gate on the same depot record)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct tank/pipeline-equipment control)
     - Finalizing a tank/pipeline-integrity clearance (`:finalize-tank-integrity-clearance? true`) is a PERMANENT, unconditional block
     - Finalizing a hazmat-storage-safety clearance (`:finalize-hazmat-storage-safety-clearance? true`) is a PERMANENT, unconditional block
     - A delivery may not push a depot's own recorded inventory past its own logged tank capacity (independently recomputed)
     - No double-scheduling the same delivery operation; no double-coordinating the same supplier order
     - No fabricated `:fuel-type` value on a shipment-record patch
     - No physically implausible `:quantity-liters` value on a shipment-record patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Supplier orders whose own `:order-value-usd` is independently recomputed to be at or above the cost threshold always escalate, regardless of confidence
     - Low-confidence proposals
3. **`fueldepot.phase`** (Phase 0->3 rollout): `:schedule-delivery-operation`/`:flag-safety-concern`/`:coordinate-supplier-order` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-shipment-record` may auto-commit at phase 3 when clean
4. **`fueldepot.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

### Boolean-flag scope-exclusion checks, not text-search (self-tripping-bug avoidance)

Every permanent scope-exclusion check in `fueldepot.governor` tests a dedicated, explicitly-named boolean flag on the proposal's own `:value` (`:finalize-tank-integrity-clearance?` / `:finalize-hazmat-storage-safety-clearance?`) — never a substring/term-list scan over the advisor's own free-text `:summary`/`:rationale`. Flag names are phrased as the finalization/execution ACTION ("finalize the tank-integrity clearance"), not a bare noun a legitimate rationale sentence could innocently contain ("...tank integrity...", "...safety..."). `fueldepot.governor-contract-test`'s `default-mock-advisor-proposals-never-self-trip-scope-exclusion-checks` test asserts directly that none of the four ops' own default mock-advisor proposals ever trip either permanent block on a clean, verified happy path.

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
