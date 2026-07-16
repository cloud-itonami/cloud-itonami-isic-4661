# ADR-0001: FuelDepotAdvisor ⊣ Fuel Depot Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-4661` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-4661` publishes an OSS blueprint for ISIC 4661
(wholesale of solid, liquid and gaseous fuels and related products)
**fuel-wholesale-depot operations coordination** (fuel-receipt/
dispatch/inventory-level data logging, tanker-truck/pipeline
delivery-operation scheduling, safety-concern flagging, and supplier-
procurement-order coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase
0->3 rollout pattern established across the cloud-itonami fleet.

ISIC 4661 sits in a domain with a direct hazmat-handling and
storage-safety dimension: fuel depots store solid (coal, coke), liquid
(petrol, diesel, kerosene, heavy fuel oil, lubricating oil), and
gaseous (LPG, CNG, LNG) products under tank/pipeline infrastructure
whose integrity is a physical-safety matter, not merely a business
record. This build is deliberately scoped as **DEPOT operations
coordination**, not direct tank/pipeline-safety authority and not
fuel-transfer control -- the closest domain analog for the
plant/depot-operations *shape* is `cloud-itonami-isic-2029`
(Manufacture of other chemical products n.e.c., illustrated by
adhesives/glues manufacturing): both are back-office coordination
actors for a fixed processing/storage facility with heavy equipment (a
tank/pipeline analog to 2029's reactor/mixing-tank), a real physical
safety dimension, and the same four-op shape (an administrative log
op, a scheduling op, an always-escalating safety-concern op, and a
coordination op) gated by a verified/registered ground-truth check on
the central facility record.

This vertical also sits alongside `cloud-itonami-isic-4671` (wholesale
of solid, liquid and gaseous fuels, ISIC Rev.5 numbering, already
`:implemented` in this fleet), but the two are architecturally
distinct rather than duplicative: 4671 models bulk fuel **TRADING**
(counterparty credit clearance, sanctions screening, contract-on-file
verification ahead of a real delivery dispatch or invoice settlement --
a `:fuel-trading-governor`), while 4661 (this repo) models the
**DEPOT's own back-office operations coordination** -- shipment-record
logging, delivery-operation scheduling, safety-concern flagging, and
supplier-order coordination against the depot's own operating-license
and tank-capacity ground truth, with NO counterparty-credit or
sanctions-screening dimension at all. `4661`'s own governor keyword,
`:fuel-depot-operations-governor`, is grep-verified UNIQUE fleet-wide
(`gh search code "fuel-depot-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created), distinct from
4671's own `:fuel-trading-governor`; so is the `fueldepot` namespace
prefix (`gh search code "fueldepot" --owner cloud-itonami`, zero
hits), distinct from 4671's own `fueltrade` namespace prefix.

This vertical has NO pre-existing `kotoba-lang/fueldepot`-style
capability library to wrap (verified: no such repo exists; also
distinct from 4671's own self-contained design decision, made for the
same "no pre-existing library" reason). This build therefore uses
self-contained domain logic -- pure functions in `fueldepot.registry`
(depot verification, delivery-capacity recompute, fuel-type
validation, quantity plausibility validation, order-value-threshold
recompute) are re-verified independently by the governor, the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2029`'s `adhesivemfg.registry`).

## Decision

### Decision 1: Self-contained domain logic (no external fuel-depot-operations capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
fuel-depot vertical has NO pre-existing capability library to wrap.
The depot-verification / delivery-capacity / fuel-type / quantity
validation functions live as pure functions in `fueldepot.registry`
and are re-verified independently by `fueldepot.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors (most directly `cloud-itonami-isic-2029`'s
`adhesivemfg.registry`).

### Decision 2: DEPOT operations coordination, not tank/pipeline control, not fuel-transfer control, and NOT a hazmat-storage-safety-clearance authority -- scope boundary at the back-office

This actor is **strictly back-office coordination** of fuel-wholesale-
depot operations. It does NOT:
- Control tank/pipeline equipment directly (no valve actuation, no pump control)
- Make depot-safety or storage-safety decisions (exclusive to the human depot supervisor)
- Finalize a tank/pipeline-integrity clearance
- Finalize a hazmat-storage-safety clearance (exclusive to a safety-certification authority)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human depot-supervisor
approval. This is not a replacement for the supervisor's authority or
a certification authority's process — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: fuel-wholesale-depot operations is a
hazmat-handling and storage-safety-critical domain (leak/spill/tank-
integrity hazard). Safety-concern flagging NEVER auto-commits. All
safety concerns escalate immediately to human review, and no proposal
may ever attempt to finalize a tank/pipeline-integrity clearance or a
hazmat-storage-safety clearance, regardless of confidence or phase.

### Decision 3: Boolean-flag scope-exclusion checks, not text-search over the advisor's own rationale (self-tripping-bug avoidance)

A sibling-fleet bug class has recurred across multiple independently-
built actors in this fleet: a governor's own scope-exclusion term list
is sometimes phrased as a bare noun (e.g. bare "safety"), which then
accidentally matches inside the mock advisor's own DEFAULT rationale/
disclaimer text for a legitimate, allowed proposal -- causing the
actor to self-block on its own happy path. This build avoids the bug
class structurally rather than merely by careful wording: both
permanent scope-exclusion checks
(`tank-integrity-clearance-finalize-blocked-violations`,
`hazmat-storage-safety-clearance-finalize-blocked-violations`) test a
dedicated, explicitly-named BOOLEAN FLAG on the proposal's own
`:value` (`:finalize-tank-integrity-clearance?` /
`:finalize-hazmat-storage-safety-clearance?`), the same discipline
`adhesivemfg.governor`'s own `:actuate-line?`/`:decide-certification?`
flags establish -- never a substring/term-list scan over free text.
Flag names are additionally phrased as the finalization/execution
ACTION ("finalize the tank-integrity clearance", "finalize the
hazmat-storage-safety clearance") rather than a bare noun a legitimate
rationale sentence could innocently contain. A dedicated regression
test, `fueldepot.governor-contract-test`'s
`default-mock-advisor-proposals-never-self-trip-scope-exclusion-checks`,
asserts directly that none of the four ops' own default mock-advisor
proposals (including one whose rationale text deliberately contains
the words "integrity" and "safety" in a legitimate safety-concern
description) ever trips either permanent block against clean, verified
fixture data.

### Decision 4: One shared depot-verification gate, not two separate entity kinds

Unlike `cloud-itonami-isic-2029` (which gates two distinct entity
kinds -- equipment for maintenance scheduling, batch for shipment
coordination), this vertical's own HARD invariant ("depot/license
record must be independently verified/registered before any action")
is phrased around a SINGLE entity: the depot's own operating-license
record. Both `:schedule-delivery-operation` and
`:coordinate-supplier-order` therefore gate on the SAME `depot-ready?`
ground-truth check (`fueldepot.registry/depot-ready?`) against the
SAME `depots` entity map in `fueldepot.store`, referenced by
`:depot-id` in each proposal's own `:value`. `:schedule-delivery-
operation` additionally independently recomputes whether a depot's own
recorded on-hand inventory plus the proposal's own claimed quantity
would exceed the depot's own recorded tank capacity
(`fueldepot.registry/delivery-capacity-exceeded?`) — never taken on
the advisor's self-report. Overfilling a storage tank past its own
rated capacity is a real physical hazard, not merely an accounting
overage, which is why this is a HARD hold rather than a soft
escalation (distinct from the order-value threshold in Decision 5,
which is a legitimate business act a human may approve).

### Decision 5: Supplier-order cost-threshold escalation, independently recomputed

`:coordinate-supplier-order` proposals whose own declared
`:order-value-usd` is independently recomputed
(`fueldepot.registry/order-value-exceeds-threshold?`) to be at or
above `default-order-value-threshold-usd` (250,000 USD, a
representative wholesale-scale bulk-fuel restock order) ALWAYS
escalate to a human procurement approver, regardless of the advisor's
own reported confidence — folded into the same `stakes?` gate
`:flag-safety-concern`'s own `high-stakes` membership uses, so a large
order and a safety concern share the identical soft-escalation
machinery. This is a SOFT gate (a human may approve), unlike the HARD,
PERMANENT blocks in Decision 3 — placing a large legitimate order is
not itself a scope violation, but it always deserves human eyes.

### Decision 6: HARD invariants (no override)

Four HARD governor invariants (elaborated into eleven concrete checks
in `fueldepot.governor`, mirroring `cloud-itonami-isic-2029`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Depot/license record must be independently verified/registered before any action is taken against it (delivery-operation scheduling and supplier-order coordination both gate on the same depot record), and a delivery's quantity must independently recompute within the depot's own logged tank capacity
2. Proposals must be `:effect :propose` only (never direct tank/pipeline-equipment control)
3. Direct tank/pipeline-equipment control, a tank/pipeline-integrity-clearance finalization, or a hazmat-storage-safety-clearance finalization is permanently blocked
4. The op allowlist is closed — `:log-shipment-record`/`:schedule-delivery-operation`/`:flag-safety-concern`/`:coordinate-supplier-order` only

## Consequences

(+) Fuel-wholesale-depot operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control, not a clearance authority"
boundary is explicit in code: all `:effect :propose`, all real-world
tank/pipeline actuation requires human depot-supervisor sign-off, and
no path exists for this actor to finalize a tank/pipeline-integrity or
hazmat-storage-safety clearance.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into eleven concrete governor checks) protect against scope creep into
unauthorized tank/pipeline equipment operation, clearance finalization,
or non-compliant fuel-type/quantity data. Safety concerns and
above-threshold supplier orders are circuit-breakers, not thresholds
a compromised advisor could quietly tune around.

(+) The scope-exclusion checks are structurally immune to the
sibling-fleet self-tripping-bug class (Decision 3): boolean-flag-based,
action-phrased, and regression-tested against every op's own default
advisor proposal.

(-) Still a simulation/proposal layer, not a real depot-operations
control system. Tank/pipeline actuation and fuel transfer remain
human-controlled via external channels, and hazmat-storage-safety/
tank-integrity clearance decisions remain a certification-authority
process entirely outside this actor.

(-) No integration with real depot-management databases (tank
telemetry, SCADA, freight dispatch, or an authoritative hazmat-
storage-safety-certification database) — this is a standalone
coordinator blueprint; the closed `valid-fuel-types` set and quantity/
order-value thresholds are representative, illustrative values, not an
exhaustive multi-jurisdiction regulatory specification database.

## Verification

- `cloud-itonami-isic-4661`: `clojure -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:dev:run` demo narrative exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, depot-not-verified for both gated ops,
  delivery-capacity-exceeded, tank-integrity-clearance-finalize-
  blocked, hazmat-storage-safety-clearance-finalize-blocked,
  already-scheduled, already-ordered, invalid-fuel-type,
  invalid-quantity, high-value-order escalation).
- A dedicated regression test
  (`default-mock-advisor-proposals-never-self-trip-scope-exclusion-
  checks`) asserts the sibling-fleet self-tripping-bug class cannot
  occur here.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
