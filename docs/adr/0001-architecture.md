# ADR-0001: cloud-itonami-isic-6612 -- Broker-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820` ADR-0001s (the pattern this ADR ports; `6512`'s/`6530`'s/
  `6820`'s ADRs establish the "write the lesson down, don't just fix
  it" discipline this build reapplies proactively for two DIFFERENT
  established lessons), ADR-2607032000 (`cloud-itonami` insurance (ISIC
  65/66) + real-estate (ISIC 68) coverage push -- now fully closed by
  `6820`'s Addendum 7)
- Context: The original 8-repo batch from ADR-2607032000 closed with
  `6820` (real estate). This ADR is the FIRST build under the standing
  "pick a new ISIC blueprint vertical" direction to select a target
  OUTSIDE that ADR's original scope: `cloud-itonami-isic-6612`
  (security and commodity contracts brokerage, ISIC division 64/66),
  deepened from `:blueprint` to `:implemented`, the ninth actor in this
  fleet.

## Problem

Securities/commodity brokerage bundles several distinct real-world
concerns under one governed workflow:

1. **Jurisdiction broker-dealer registration/disclosure correctness**
   -- is the required evidence for executing a trade based on an
   official regulator, or invented?
2. **Trade arithmetic correctness** -- does a claimed trade value
   actually match quantity times price? The SAME "never trust a
   claimed number, independently re-derive it" discipline `cloud-
   itonami-isic-6629`'s/`6520`'s/`6820`'s checks establish, applied to
   a FIFTH domain-specific formula, as an exact-match (a trade has
   exactly one correct notional value).
3. **Conflict-of-interest screening** -- does the broker (or the
   account) carry an undisclosed conflict of interest? Structurally
   identical to `casualty.governor/sanctions-violations`'s/
   `adjustment.governor`'s/`intermediation.governor`'s party-screening
   shape -- reused here for a THIRD time.
4. **Suitability screening, a GENUINELY NEW kind of check for this
   fleet** -- does an order's risk level actually match the client's
   own risk tolerance? Not an arithmetic check, not a party-screening
   check -- a COMPATIBILITY-matching check between two independent
   attributes (account risk-profile, order risk-level), evaluated with
   the SAME unconditional-evaluation discipline conflict-of-interest
   uses, but for a structurally different kind of question.
5. **Real actuation, but only ONE this time** -- executing a real
   trade on a client's behalf is the SOLE real-world act this actor
   performs, unlike five of its eight siblings' dual-actuation shape.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run securities brokerage with an LLM" but
"seal the LLM inside a trust boundary and layer evidence-sufficiency,
trade-arithmetic correctness, conflict-of-interest screening,
suitability-compatibility screening, audit and human-approval on top
of it, while structurally fixing the one real actuation event as
human-only."

## Decision

### 1. Broker-LLM is sealed into the bottom node; it never executes directly

`brokerage.brokerllm` returns exactly six kinds of proposal: intake
normalization, jurisdiction registration/disclosure checklist,
conflict-of-interest screening, suitability screening, order-filing
normalization, and trade-execution draft. No proposal writes the SSoT
or commits a real trade execution directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 brokerage operation

`brokerage.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. Single actuation event -- a deliberate return to the single-actuation shape

`brokerage.governor`'s `high-stakes` set has exactly ONE member
(`:actuation/execute-trade`), matching `6511`'s/`6621`'s/`6629`'s
single-actuation shape, not the dual-actuation shape five of the eight
prior actors settled into (`6512`/`6622`/`6520`/`6530`/`6820`). This
domain genuinely has only one real-world financial act (executing a
trade); forcing a second actuation event (e.g. splitting order filing
into its own actuation) would misrepresent the domain to manufacture
symmetry with the dual-actuation siblings.

### 4. `suitability-failure-violations` reuses the UNCONDITIONAL-evaluation discipline for a GENUINELY NEW kind of screening question

`conflict-of-interest-violations` reuses `casualty.governor/sanctions-
violations`'s fix (evaluated unconditionally, not scoped to a specific
op, so the screening op itself can HARD-hold on its own finding) for a
THIRD time in this fleet (after `6621`'s and `6622`'s reuses).
`suitability-failure-violations` applies the SAME unconditional-
evaluation MECHANISM to a check that is structurally DIFFERENT in kind
from every party-screening check so far: it is a compatibility match
between an order's `:risk-level` and an account's `:risk-profile`
(`brokerage.registry/suitable-for-account?`), not an identity/
relationship screen. This is this build's genuinely new contribution:
the unconditional-evaluation discipline is not inherently tied to
identity-screening -- it applies to ANY screening op whose finding must
be able to HARD-hold a later actuation, and this build proves that by
applying it to a compatibility-matching concern instead.

### 5. `trade-value-mismatch-violations` reuses the EXACT-MATCH independent-recompute pattern on a fifth formula

`brokerage.registry/compute-order-value` independently recomputes an
order's notional value (quantity times price) and the governor compares
this recompute against the order's OWN claimed value -- reusing
`cloud-itonami-isic-6629`'s/`6520`'s/`6820`'s exact-match discipline.
This is the simplest formula in the fleet so far (a single
multiplication, no formula branching by type), and is documented as
such rather than dressed up as more complex than it is.

### 6. `account-not-active-violations` checks `:status :active` directly, safely -- the fifth lifecycle to reuse this reasoning

Like `reinsurance.governor/treaty-not-bound-violations`, `pension.
governor/member-not-in-payout-violations`, and `realty.governor/
property-not-under-management-violations`, a brokerage account's
status never regresses out of `:active` once entered, so checking
`:status` directly here is safe -- the SAME reasoning already written
down three times, reapplied here without needing to rediscover it by a
failing demo.

### 7. `spec-basis-violations` proactively guards on entity-existence -- the SECOND build to apply this lesson from the start

Following `cloud-itonami-isic-6820`'s explicit application of
`6530`'s lesson, this build's `spec-basis-violations` (scoped to
`:jurisdiction/assess` + `:trade/execute`, where `:trade/execute` ALSO
carries `order-missing-violations`) guards on `(store/order st
subject)` existing FIRST, from the very first draft. This is now the
THIRD actor to apply this specific guard correctly from the start
(`6820`, and now `6612`), reinforcing it as a load-bearing convention
whenever a spec-basis check shares an op with a missing-entity check.

### 8. No real bug this time -- both established lessons applied correctly from the first draft AND first demo run

Unlike `6512` (a real sanctions-scoping bug), `6530` (a real spec-
basis/missing-entity overlap bug), and `6820` (two real bugs, one in
data-modeling and one in demo authoring), this build's demo and full
test suite passed clean on the FIRST attempt for the actor's own logic.
The ONE test-authoring slip caught (`suitability-failure-is-held-and-
unoverridable` initially asserted against the FIRST ledger entry, but
the test's own `file-order!` pre-step auto-commits first, making the
suitability hold the SECOND entry) was caught immediately by the test
run itself failing loudly -- not a silent/misleading pass, and not a
governance-logic bug at all, just an assertion targeting the wrong
ledger position.

### 9. No fabricated international trade/execution-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a trade-execution reference
number. `brokerage.registry` therefore does not invent one; it
validates required fields and assigns a jurisdiction-scoped sequence
number only.

### 10. Relationship to `kotoba-lang/securities`

This fleet's FIRST actor whose capability lib is `kotoba-lang/
securities` (position/trade/fund-NAV/mandate contracts) -- but the same
self-contained-sibling posture holds: no code dependency.

## Consequences

- (+) Securities/commodity brokerage gets the same governed, auditable-
  actor treatment as the eight prior actors, extending the pattern for
  the first time OUTSIDE ADR-2607032000's original insurance/real-
  estate scope -- any licensed broker-dealer can fork and run their
  own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/brokerage/phase_test.clj`'s `trade-
  execute-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/brokerage/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) `suitability-failure-violations` demonstrates the unconditional-
  evaluation discipline generalizes beyond identity/relationship
  screening (Decision 4) -- a genuine structural contribution, not just
  another party-screen.
- (+) Two established lessons (unconditional evaluation, proactive
  existence-guarding) were BOTH applied correctly from the first draft,
  and the demo passed clean on the first run -- evidence the "write the
  lesson down" discipline is compounding across builds rather than each
  build rediscovering the same classes of bugs.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `brokerage.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `compute-order-value` models only quantity times price, not a
  full trade's real-world terms (commissions, exchange fees, multi-leg
  orders, partial fills are out of scope -- see that fn's own
  docstring); `suitable-for-account?` models only a coarse risk-
  tolerance match, not a full suitability analysis (concentration
  limits, time-horizon/liquidity-needs analysis, product-complexity
  tiering are out of scope -- see that fn's own docstring); real
  market-access/execution/clearing integration and tax/regulatory
  reporting are all out of scope for this OSS actor -- each operator's
  responsibility (see README's coverage table).
- 37 tests / 163 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6612` at `:blueprint` only | ❌ | The standing "pick a new ISIC blueprint vertical" direction continues past the original 8-repo batch; leaving this fleet's most natural next candidate unbuilt would stall momentum for no reason |
| Split order filing into its own actuation event, for consistency with the dual-actuation majority | ❌ | This domain genuinely has one real-world financial act; manufacturing a second actuation event to match sibling shape would misrepresent the domain, the same judgment `6511`'s/`6621`'s/`6629`'s single-actuation shape already reflects |
| Model a full suitability analysis (concentration limits, time-horizon, product complexity) for conformance-test rigor | ❌ | Genuinely more complex real-world suitability regulation that this R0 does not claim to model correctly -- honestly scoped to a coarse risk-tolerance match instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Scope the unconditional-evaluation fix to conflict-of-interest only, treating suitability as a simple op-scoped check since it's "not identity screening" | ❌ | The exact same op-sharing risk applies regardless of WHAT the screening op screens for -- `:suitability/screen` needs the same ability to HARD-hold on its own finding as `:conflict/screen` does, and applying the discipline generically (Decision 4) is the more honest generalization |
| Require `kotoba.securities` (the capability lib) directly from `brokerage.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
