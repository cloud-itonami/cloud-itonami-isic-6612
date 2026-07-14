# wasm/ — kotoba-wasm deployment of the trade-value-mismatch recompute

`trade_value_mismatch.kotoba` is a port of `brokerage.governor`'s
`trade-value-mismatch-violations` check (the independent recompute
`brokerage.registry/compute-order-value` runs against an order's OWN
claimed `:claimed-order-value`, gated in-kernel by
`brokerage.kernels.gate/value-mismatch` -- see `src/brokerage/
governor.cljc`'s ns docstring, check 7) into the minimal `.kotoba`
language subset, compiled to a real WASM module via `kotoba wasm emit`,
and hosted via `kototama.tender` (`test/wasm/trade_value_mismatch_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`, and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` established
(ADR-2607062330 addendum 5) -- `trade_value_mismatch.kotoba` is the
closest analog to `fee_accrual.kotoba`: a formula recompute (quantity
times price) over integer inputs, no host imports, no floats.

## Why the source differs from `brokerage.governor`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter, same finding `cloud-itonami-isic-6492`/`-6511`/`-6630`
document). The port therefore:

- Ports ONLY the pure ground-truth arithmetic core -- recompute
  `quantity * price` and compare it against the order's own claimed
  value -- never the `store/order` lookup, the op-dispatch (`:trade/
  execute`), or the in-kernel `hard-violation`/`verdict-code`
  aggregation, all of which stay in Clojure and never get ported (no
  maps, no protocols, no op-keyword dispatch in the wasm-compilable
  subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset), matching every
  prior port's convention.
- Represents `price` and the order's claimed value in the SAME `x100`
  fixed-point convention `brokerage.governor/value->x100` already uses
  host-side (`(Math/round (* 100.0 (double v)))`, i.e. cents) instead of
  doubles -- avoids floating point entirely, the same "no floats in the
  wasm-compilable subset" constraint `affordability.kotoba`'s integer
  cross-multiplication and `fee_accrual.kotoba`'s basis-point/
  hundredths-of-a-year scaling work around. `quantity` is a unit-less
  share/contract count and needs no scaling of its own -- multiplying it
  by an already-x100 `price-x100` directly yields an x100 notional value
  (`quantity * price-x100 = quantity * price * 100 = (quantity * price)
  * 100`), so this port needs only ONE multiply and no `quot`, unlike
  `fee_accrual.kotoba`'s three-factor product-then-divide.
- Compares the recomputed value against `claimed-value-x100` with a
  plain `=` -- `brokerage.governor`'s own in-kernel check
  (`gate/value-mismatch`) allows a `value-tolerance-x100` of exactly `1`
  (one cent), which only ever passes when both x100 integers are
  IDENTICAL (a difference of 1 already fails the `< 1` test -- see
  `gate.cljc`'s own battery: `5000000`/`5000001` mismatches). That
  tolerance exists purely to absorb the JVM double-rounding noise
  `value->x100` performs at the Clojure/host boundary, not to
  intentionally permit a real currency-unit discrepancy -- once both
  sides are pre-scaled to exact x100-cent integers before crossing into
  the guest, there is no rounding noise left to tolerate, so an exact
  `=` is the faithful reduction of "differ by less than 1 cent" over
  integers, the same reasoning `fee_accrual.kotoba`'s README documents.

This is a simpler port than `fee_accrual.kotoba`: one multiply, no
`quot`, no zero-guard branch (an order with `quantity 0` has a true
recomputed value of `0`, which the comparison handles naturally, same as
`fee_accrual.kotoba`'s zero-basis case).

**Known scope limit (i32 range):** the guest computes `quantity *
price-x100` in a single 32-bit WASM `i32.mul`, so that product must stay
under the signed i32 ceiling (~2.147e9) or it silently wraps instead of
trapping -- e.g. `quantity 100` at `price-x100 50000` ($500.00/unit)
gives `5,000,000`, comfortably inside range; a very large block trade
(millions of shares at a high price) could approach the ceiling. This is
a PoC-scale limitation, not a design claim that the formula holds for
every real trade size. Raising it would mean promoting to `i64`
arithmetic (`i64*`/`i64-` exist in the subset, see `compile-wasm-expr`),
a follow-up, not attempted in this pass.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba`,
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba`, and
`cloud-itonami-isic-6630`'s `fee_accrual.kotoba` use. A host writes three
little-endian i32 values before calling `main()`:

| offset | field                 | unit                                                          |
|--------|-----------------------|----------------------------------------------------------------|
| 0      | `quantity`             | unit-less share/contract count, unscaled                       |
| 4      | `price-x100`           | per-unit price, x100 (cents) -- same scale `value->x100` uses  |
| 8      | `claimed-value-x100`   | the order's own claimed notional value, x100 (cents)           |

`main()` returns `1` (the recomputed notional value -- `quantity *
price-x100` -- matches the claim) or `0` (mismatch --
`brokerage.governor`'s `:trade-value-mismatch` HARD violation). All three
offsets are well below `heap-base` (2048), so they never collide with
anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6612/wasm/trade_value_mismatch.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6612/wasm/trade_value_mismatch.wasm --json
```

Fleet deployment: not attempted in this pass -- see
cloud-itonami-isic-6492/6511 for the established pattern.
