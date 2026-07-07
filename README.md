# cloud-itonami-isic-6612

Open Business Blueprint for **ISIC Rev.5 6612**: Security and commodity
contracts brokerage. This repository publishes a securities/commodity
brokerage actor -- client-account intake, order filing and trade
execution on a client's behalf -- as an OSS business that any
qualified, licensed broker-dealer can fork, deploy, run, improve and
sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820)) --
the first outside ISIC division 65/66/68, into division 64/66's
brokerage/securities space. Here it is **Broker-LLM ⊣ Brokerage
Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> suitability summary, normalizing account intake, and running the
> trade-value arithmetic -- but it has **no notion of which
> jurisdiction's broker-dealer registration/disclosure requirements are
> official, no license to execute a real trade on a client's behalf,
> no way to know on its own whether a claimed trade value actually
> matches quantity times price, and no way to know whether an order's
> risk level actually matches the client's own risk tolerance**.
> Letting it execute a trade directly invites fabricated jurisdiction
> citations, silently-wrong trade math a client would actually rely on,
> undisclosed conflicts of interest, and unsuitable recommendations --
> and liability for whoever runs it. This project seals the Broker-LLM
> into a single node and wraps it with an independent **Brokerage
> Governor**, a human **approval workflow**, and an immutable **audit
> ledger**.

## Scope: what this actor does and does not do

This actor covers client-account intake through order filing and trade
execution, gated by conflict-of-interest and suitability screening. It
does **not**, by itself, hold a license to broker securities/commodity
contracts in any jurisdiction, and it does not claim to. It also does
**not** model a full trade's real-world terms -- no commissions, no
exchange fees, no multi-leg orders, no partial fills (see `brokerage.
registry/compute-order-value`'s own docstring for the honest
simplification this makes: quantity times price, not a full trade
blotter). Its suitability check is likewise a simplified risk-
tolerance-matching rule, not a full analysis (no concentration limits,
no time-horizon/liquidity-needs analysis, no product-complexity
tiering -- see `suitable-for-account?`'s own docstring). Whoever
deploys and operates a live instance (a licensed broker-dealer)
supplies the jurisdiction-specific license, the real market-access/
execution infrastructure, and bears that jurisdiction's liability --
the software supplies the governed, spec-cited, audited execution
scaffold so that operator does not have to build the compliance layer
from scratch for every new market.

### Actuation

**Executing a real trade on a client's behalf is never autonomous, at
any phase, by construction.** Two independent layers enforce this
(`brokerage.governor`'s `:actuation/execute-trade` high-stakes gate and
`brokerage.phase`'s phase table, which never puts `:trade/execute` in
any phase's `:auto` set) -- see `brokerage.phase`'s docstring and
`test/brokerage/phase_test.clj`'s `trade-execute-never-auto-at-any-
phase`. The actor may draft, check and recommend; a human broker is
always the one who actually executes a trade. Unlike five of its eight
siblings (`6512`/`6622`/`6520`/`6530`/`6820`'s dual-actuation shape),
this actor has exactly ONE actuation event -- matching `6511`'s/
`6621`'s/`6629`'s single-actuation shape.

## The core contract

```
account intake + jurisdiction facts (brokerage.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Broker-LLM   │ ─────────────▶ │ Brokerage Governor        │  (independent system)
   │  (sealed)    │  + citations    │ spec-basis · evidence-     │
   └──────────────┘                 │ incomplete · account-not-   │
                             commit ◀────┼──────────▶ hold │ active · order-missing ·
                                 │             │           │ conflict-of-interest ·
                           record + ledger  escalate ─▶ human   suitability-failure ·
                                             (ALWAYS for         trade-value-mismatch
                                              :trade/execute)     (independent recompute)
                                                                  · double-execution
```

**The Broker-LLM never executes a trade the Brokerage Governor would
reject, and never does so without a human sign-off.** Hard violations
(fabricated jurisdiction requirements; unsupported KYC/suitability
evidence; an order filed against an inactive account; a nonexistent
order; an undisclosed conflict of interest; an unsuitable order; a
trade value that doesn't match this vehicle's own independent
recompute; a double execution) force **hold** and *cannot* be approved
past; a clean execution proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean intake-through-execution lifecycle + seven HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a document-courier and kiosk
robot collects in-person account-opening signatures, under the actor,
gated by the independent **Brokerage Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Brokerage Governor, trade-execution draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6612`). Related capability contracts (position/trade/fund-NAV/mandate
shapes) are published as [`kotoba-lang/securities`](https://github.com/kotoba-lang/securities);
this actor's `brokerage.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship every prior actor has toward
its own capability lib.

## Layout

| File | Role |
|---|---|
| `src/brokerage/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + trade-execution history |
| `src/brokerage/registry.cljc` | Trade-execution draft records, plus `compute-order-value` (REAL, trivial quantity-times-price formula) and `suitable-for-account?` (REAL, simplified risk-tolerance-matching rule) -- see docstrings for what neither models |
| `src/brokerage/facts.cljc` | Per-jurisdiction broker-dealer registration/disclosure catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/brokerage/brokerllm.cljc` | **Broker-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/conflict-screening/suitability-screening/filing/execution proposals |
| `src/brokerage/governor.cljc` | **Brokerage Governor** -- 7 HARD checks (spec-basis · evidence-incomplete · account-not-active · order-missing · conflict-of-interest, unconditional evaluation · suitability-failure, unconditional evaluation · trade-value-mismatch, independent recompute) + double-execution guard + 1 soft (confidence/actuation gate) |
| `src/brokerage/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (execution always human; account intake + order filing auto-eligible, no capital risk) |
| `src/brokerage/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/brokerage/sim.cljc` | demo driver |
| `test/brokerage/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers client-account intake through order filing and trade
execution -- the core governed lifecycle this blueprint's own `docs/
business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Account intake + per-jurisdiction registration/disclosure checklisting, HARD-gated on an official spec-basis citation (`:account/intake`/`:jurisdiction/assess`) | Commissions, exchange fees, multi-leg orders, partial fills (see `compute-order-value`'s docstring) |
| Conflict-of-interest screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:conflict/screen`) | Full suitability analysis (concentration limits, time-horizon/liquidity-needs analysis, product-complexity tiering -- see `suitable-for-account?`'s docstring) |
| Suitability screening against a real, simplified risk-tolerance-matching rule (`:suitability/screen`) | Real market-access/execution/clearing integration, tax/regulatory reporting |
| Order filing against an active account (`:order/file`, HARD-gated on the account actually being active) | |
| Trade execution, independently re-verified against this vehicle's OWN quantity-times-price recompute, with a double-execution guard (`:trade/execute`) | |
| Immutable audit ledger for every intake/assessment/screening/filing/execution decision | |

Extending coverage is additive: add the next gate (e.g. a commission-
calculation op) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`brokerage.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `brokerage.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `brokerage.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Broker-LLM` + `Brokerage Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, modeled closely on the eight prior actors'
architecture. See `docs/adr/0001-architecture.md` for the history and
design.

## License

Code and implementation templates are AGPL-3.0-or-later.
