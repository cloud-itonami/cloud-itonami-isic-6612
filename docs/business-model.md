# Business Model: Security and commodity contracts brokerage

## Classification

- Repository: `cloud-itonami-isic-6612`
- ISIC Rev.5: `6612`
- Activity: security and commodity contracts brokerage -- executing securities/commodity trades on behalf of clients
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent broker-dealers
- cooperative trading desks
- community investment-access programs

## Offer

- client-account intake
- suitability disclosure proposal
- trade-execution proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per book-of-business
- support: monthly retainer with SLA
- migration: import from an incumbent brokerage system
- per-trade fee

| Package | Customer | Price shape |
|---|---|---|
| Self-host starter | broker-dealer ops lead | one-time implementation fee |
| Managed Starter | one independent broker-dealer / trading desk, unlimited rep seats | ¥35,000/月 flat |
| Per-trade | desk billing its own book | fee per executed order |
| Operator enablement | newly registered broker-dealer | training + certification |

**Market-anchored (2026-08-10)**: benchmarked against 6 real competitor
products, priced for an illustrative small independent broker-dealer /
advisory trading desk with 5–15 registered reps and roughly 300 client
accounts. **Only 2 of the 6 could be confirmed from the vendor's own page.**
This is the least price-transparent of the markets in this fleet, and the
opacity is not evenly distributed — the *analytics* vendors publish, the
*compliance and order-supervision* vendors do not:

| Product | Discloses | Published price | Source |
|---|---|---|---|
| Nitrogen (ex-Riskalyze; risk-tolerance / suitability) | yes | Risk Center **$199/mo**, Research Center **$149/mo**, Income/Tax/Legacy Center **$79/mo**, Nitrogen Elite **$395/mo**, Nitrogen Complete **$450/mo** (annual term); Enterprise contact-only | <https://nitrogenwealth.com/pricing/> |
| Altruist (custody + trading platform) | yes | Altruist One **0.01% per month** (asset-based); **$1 minimum monthly fee per account** on High-Yield Cash; Hazel **$60 per seat monthly** | <https://altruist.com/one/> |
| SmartRIA (RIA/BD compliance management) | **no** | 非公開 — no list pricing published; routes to a demo | <https://smart-ria.com/faqs/> |
| COMPLY (ex-RIA in a Box / ComplySci) | **no** | 非公開 — demo required to learn the price | <https://www.stratifi.com/blog/best-finance-compliance-software> |
| Orion Advisor Tech (broker-dealer compliance / trading / portfolio accounting) | **no** | 非公開 — there is no pricing page at all; every path is "Get a Demo" / "Request a Consultation" | <https://orion.com/who-we-serve/broker-dealers> |
| Kwanti (portfolio analytics) | page exists but returns HTTP 429 | third-party aggregators report $195–$224/mo — **not adopted**, no primary source | <https://kwanti.com/pricing> |

Converting at ~¥150/$: Nitrogen spans **¥29,850/月** (Risk Center alone) to
**¥67,500/月** (Complete). Altruist's software subscription is account-based
rather than seat-based; at ~300 accounts it lands in the same neighbourhood,
**~¥30,000/月** order of magnitude. The confirmed band is therefore
**¥29,850–67,500/月**. Japanese brokerage back-office systems (THE STAR and
its peers) publish nothing either, so no JP anchor exists.

**¥35,000/月 sits just above Nitrogen's Risk Center and at about half of
Nitrogen Complete.** Risk Center is the closest functional neighbour — it is
the product that does risk-tolerance matching — and this actor performs the
same suitability match against the client's own stated risk tolerance, so that
is the natural floor. It is set slightly above rather than at that floor
because the actor also carries a supervision layer that Nitrogen does not:
a conflict-of-interest screen, an **independent recompute of the order value**
(quantity × price) against what the model claimed, a block on orders filed
against an inactive account, and a double-execution check off its own
execution history — each of which forces a hold that approval cannot override,
recorded in an immutable ledger. It is not set near the top of the band
because Nitrogen Complete is a five-centre bundle (research, income, tax,
legacy) while this actor is a single-actuation lane, and because the actor
supplies no market access, no custody, no performance reporting, and no
portfolio analytics.

**This number is an extrapolation from an adjacent layer, and should be read
that way.** The vendors whose function this actor most directly overlaps —
SmartRIA, COMPLY, Orion — publish nothing, so the band above is built from
suitability-analytics and custody pricing rather than from
compliance-supervision pricing. If the unpriced layer is in fact more
expensive (third-party commentary puts an independent RIA's annual compliance
software spend well above this tier, though from a source that could not be
verified against a vendor), then ¥35,000/月 is conservative. Confidence in
this figure is **medium**, lower than the sibling verticals in this fleet, and
it should be revisited the first time a real quote from any of the three
opaque vendors becomes observable.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥35,000/月 flat) is available now —
[**subscribe to Managed Starter**](https://buy.stripe.com/7sYfZieuXdNK4ze922eEo0e).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. After subscribing, open an
[issue](https://github.com/cloud-itonami/cloud-itonami-isic-6612/issues/new)
to arrange managed-tenant setup (manual fulfilment today, no automated
onboarding yet). **No broker-dealer or trading desk has claimed or subscribed
to this tier yet — this is a live, working checkout with zero paid tenants,
not a claim of existing revenue.** Subscribing conveys no broker-dealer
registration and no market access: the subscriber remains the licensed broker
who executes every trade.

## Trust Controls

- no trade is executed on a client's behalf without human sign-off
- a fabricated jurisdiction registration/disclosure citation,
  unsupported KYC/suitability evidence, an order filed against an
  inactive account, an undisclosed conflict of interest, an order whose
  risk level does not match the client's own risk tolerance, or a trade
  value that does not match this vehicle's own independent recompute --
  each forces a hold, not an override
- an order cannot be executed twice: a double-execution attempt is held
  off this actor's own execution history alone, with no upstream
  comparison needed
- every intake, assessment, screening, filing and execution path is
  auditable
- emergency manual override paths remain outside LLM control
