# Operator quickstart

## Prerequisites

- **Clojure 1.11+** (or nbb for scripting)
- **Git** for cloning and version control
- (Optional) **Java 11+** if running with standard Clojure (not nbb)

This repository builds on [`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj), the same actor-runtime framework used by all cloud-itonami verticals (ISIC divisions 65, 66, 68). You do not need to clone monorepo siblings separately; dependencies are resolved via `deps.edn`.

## Run tests

Verify the governor contract, phase invariants, store parity, registry conformance, and jurisdiction-facts coverage:

```bash
clojure -M:dev:test
```

Expected: all tests pass (contract · phase invariants · store parity · registry conformance · facts coverage).

## Run the demo

Walk one clean intake-through-execution lifecycle and seven HARD-hold cases through the actor:

```bash
clojure -M:dev:run
```

This invokes the **demo driver** (`src/brokerage/sim.cljc`), which exercises:
- Account intake with jurisdiction registration checklist
- Conflict-of-interest screening
- Suitability screening (risk-tolerance matching)
- Order filing against an active account
- Trade execution with independent trade-value verification
- Seven HARD-hold scenarios (fabricated jurisdiction citation, unsupported KYC, account inactive, conflict detected, suitability failure, trade-value mismatch, double execution)

Output appears in your terminal and includes:
- Phase transitions (read-only → assisted intake → assisted assess → supervised)
- Governor decisions (hold / approve / escalate)
- Immutable audit ledger entries

## Lint

Check code style with `clj-kondo` (errors fail CI):

```bash
clojure -M:lint
```

## Where is the Governor?

The **Brokerage Governor** is the independent verification layer that enforces trade-execution policy:

**Source location:** `src/brokerage/governor.cljc`

Key functions:
- `evaluate-proposal` — 7 HARD checks + 1 soft check
- `trade-value-mismatch-violations` — independent quantity×price recompute (see `wasm/trade_value_mismatch.kotoba` for WASM PoC)
- `suitable-for-account?` — risk-tolerance-matching rule (see docstring for what it does NOT model)

**Hard checks:**
1. Spec-basis citation (official jurisdiction registration/disclosure requirement)
2. Evidence completeness (KYC/suitability documentation)
3. Account active (order not filed against inactive account)
4. Order exists (referenced order is in the store)
5. Conflict-of-interest (screened via `screen-conflict` gate)
6. Suitability (client risk tolerance matches order risk)
7. Trade-value match (independent recompute vs. claimed value)

Plus: double-execution guard (trade executed twice = HOLD).

**Operation flow:** `src/brokerage/operation.cljc` — the **OperationActor** (langgraph-clj StateGraph) orchestrates intake → assessment → conflict-screening → suitability-screening → filing → execution proposals, each routed through the Governor before any commit to the audit ledger.

**Store & ledger:** `src/brokerage/store.cljc` — `MemStore` (testing) or `DatomicStore` (production), with append-only audit ledger and trade-execution history.

## Next steps

1. **Fork this repository** from [github.com/com-junkawasaki/cloud-itonami-isic-6612](https://github.com/com-junkawasaki/cloud-itonami-isic-6612)
2. **Customize jurisdiction facts** (`src/brokerage/facts.cljc`) — add your jurisdiction's broker-dealer registration/disclosure citations
3. **Deploy the Governor** — wire `operation.cljc` into your trading desk's infrastructure
4. **Integrate real-time market data** (out of scope; see README for honest scope boundary)
5. **Set up human approval workflow** — the actor drafts; a licensed broker executes
6. **Export audit ledger** — prove every decision to regulators

See `docs/operator-guide.md` for certification requirements and minimum production controls.
