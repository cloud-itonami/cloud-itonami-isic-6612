# cloud-itonami-isic-6612

Open Business Blueprint for **ISIC Rev.5 6612**: Security and commodity contracts brokerage.

This repository designs a forkable OSS business for security and commodity contracts brokerage -- executing securities/commodity trades on behalf of clients -- run by a qualified, licensed operator so a community or
independent professional never surrenders customer data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-courier and kiosk robot collects in-person account-opening signatures,
under an actor that proposes actions and an independent **Brokerage Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + case/account records
        |
        v
Broker-LLM -> Brokerage Governor -> hold, proceed, or human approval
        |
        v
case/account ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: executing a trade on a client's behalf.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6612`). Required capabilities are implemented by:

- [`kotoba-lang/securities`](https://github.com/kotoba-lang/securities)
  -- position, trade, fund-NAV and mandate contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`Broker-LLM` + `Brokerage Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
