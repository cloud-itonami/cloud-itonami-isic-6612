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
