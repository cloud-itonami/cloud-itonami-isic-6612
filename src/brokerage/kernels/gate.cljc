(ns brokerage.kernels.gate
  "Safety kernel for the Brokerage Governor + phase gate — the decision
  CORE of `brokerage.governor/check` and `brokerage.phase/gate`,
  extracted into the safe-kotoba subset (cloud-itonami kernels
  discipline, superproject ADR-2607101200), following
  `cloud-itonami-isic-6511`'s `underwriting.kernels.gate`.

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`brokerage.governor`, `brokerage.phase`) reduce their inputs to
  flags/codes at the boundary and map the result codes back to
  keywords. `.kotoba`/wasm emission is deliberately NOT wired yet
  (owner decision 2026-07-12: ClojureScript + kotoba-datomic first);
  staying inside the subset is what keeps that door open without a
  rewrite.

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    order value int x100 (cents), rounded by the façade; the in-kernel
                `value-tolerance-x100` (1 cent) is the integer
                restatement of the façade's old |recomputed − claimed|
                < 0.01 trade-value tolerance
    op          0 read (RESERVED — this domain has NO read ops, its
                `read-ops` set is empty; code 0 is never write-enabled
                and fails closed exactly like an unknown op)
                1 :account/intake        2 :jurisdiction/assess
                3 :conflict/screen       4 :suitability/screen
                5 :order/file            6 :trade/execute
                7+ unknown write (never enabled)
    phase       0..3 (anything else: no writes at all — the façade
                normalizes unknown phases to its own default BEFORE the
                kernel, so an out-of-range phase reaching the kernel is
                a bug and fails closed)
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    reason      0 none  1 phase-disabled  2 phase-approval
    disposition 0 commit  1 escalate  2 hold

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. `:trade/execute` (op 6) —
  the ONE real-world legal/financial act this actor performs — is
  auto-enabled at NO phase: the same structural invariant the phase
  table and the governor's actuation gate state independently."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(def value-tolerance-x100
  "Trade-value tolerance in x100 (cent) integers: the integer
  restatement of the façade's old |recomputed − claimed| < 0.01 —
  after the façade rounds both sides to cents, any difference of one
  cent or more is a mismatch."
  1)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(defn abs-diff
  "Absolute difference of two integers (subset-safe: nested if + -)."
  [a b]
  (if (< a b) (- b a) (- a b)))

(defn value-mismatch
  "1 when the independently recomputed order value and the order's own
  claimed value (both x100 cent integers) differ by at least
  `value-tolerance-x100` — never trust a claimed figure as-is."
  [recomputed-x100 claimed-x100]
  (if (< (abs-diff recomputed-x100 claimed-x100) value-tolerance-x100) 0 1))

(defn trade-value-violation
  "1 when the trade-value check APPLIES (value-applies: the request is
  a `:trade/execute` and the order exists on file) AND the recomputed
  vs claimed values (x100) mismatch."
  [value-applies recomputed-x100 claimed-x100]
  (and2 (norm-flag value-applies) (value-mismatch recomputed-x100 claimed-x100)))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation is present:
  spec-basis missing / evidence incomplete / account not active /
  order missing / conflict of interest / suitability failure /
  trade-value mismatch (decided in-kernel from the x100 values) /
  double execution."
  [spec-missing evidence-incomplete account-not-active order-missing
   conflict-hit suitability-fail
   value-applies recomputed-x100 claimed-x100 double-execution]
  (or3 (or3 (norm-flag spec-missing)
            (norm-flag evidence-incomplete)
            (norm-flag account-not-active))
       (or3 (norm-flag order-missing)
            (norm-flag conflict-hit)
            (norm-flag suitability-fail))
       (or2 (trade-value-violation value-applies recomputed-x100 claimed-x100)
            (norm-flag double-execution))))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [spec-missing evidence-incomplete account-not-active order-missing
   conflict-hit suitability-fail
   value-applies recomputed-x100 claimed-x100 double-execution
   confidence-x100 actuation]
  (if (= 1 (hard-violation spec-missing evidence-incomplete account-not-active
                           order-missing conflict-hit suitability-fail
                           value-applies recomputed-x100 claimed-x100
                           double-execution))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column).
  Op 0 (the reserved read code) is enabled NOWHERE: this domain's
  `read-ops` set is empty, so unlike sibling kernels there is no read
  pass-through — every op code must be write-enabled or it holds."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 (if (= op 4) 1 0))))
      (if (= phase 3)
        (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 (if (= op 4) 1 (if (= op 5) 1 (if (= op 6) 1 0))))))
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly two cells are ever 1: phase 3 x :account/intake and
  phase 3 x :order/file (neither moves capital yet). Op 6
  (:trade/execute) is 0 at every phase — permanent structural fact,
  not a rollout milestone — and ops 3/4 (:conflict/screen,
  :suitability/screen) are likewise never auto, matching every
  sibling's KYC/conflict screening posture."
  [phase op]
  (if (= phase 3)
    (if (= op 1) 1 (if (= op 5) 1 0))
    0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `brokerage.phase/gate`:
  governor hold always wins; a write not enabled at this phase holds
  (there is no read pass-through — this domain has no read ops); a
  governor-clean write without auto rights escalates; otherwise the
  governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= 0 (op-write-enabled phase op))
      2
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 1)
        governor-disposition))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= 0 (op-write-enabled phase op))
      1
      (if (= governor-disposition 0)
        (if (= 1 (op-auto-enabled phase op)) 0 2)
        0))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict [spec evid acct miss coi suit vapp recomp claimed dbl conf act expected]
  (if (= (verdict-code spec evid acct miss coi suit vapp recomp claimed dbl conf act)
         expected)
    1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(def battery-case-count 49)

(defn battery-pass-count []
  (+
   ;; -- verdict: each hard flag dominates alone (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 1 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 1 100 0 2)
   (check-verdict 1 1 1 1 1 1 1 0 5 1 100 0 2)
   (check-verdict 1 0 0 0 0 0 0 0 0 1 100 0 2)
   ;; -- verdict: trade value (x100 cents, in-kernel tolerance)
   (check-verdict 0 0 0 0 0 0 1 5000000 5000000 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 1 5000000 5000001 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 1 5000001 5000000 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 5000000 9999999 0 100 0 0)   ; check applies to :trade/execute only
   (check-verdict 0 0 0 0 0 0 1 5000000 5000001 0  40 0 2)   ; hard mismatch wins over low confidence
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags normalize to violation (fail-closed)
   (check-verdict 7 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 100 9 1)
   (check-verdict 0 0 0 0 0 0 5 0 7 0 100 0 2)
   ;; -- phase: governor hold always wins
   (check-phase 3 1 2 2 0)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 1 5 0 2 1)
   (check-phase 2 5 0 2 1)
   (check-phase 2 6 0 2 1)
   (check-phase 3 7 0 2 1)
   ;; -- phase: op 0 (reserved read code; this domain has NO read ops)
   ;;    is write-enabled nowhere, even at phase 3 (fail-closed)
   (check-phase 3 0 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 2 0 1 2)
   (check-phase 2 3 0 1 2)
   (check-phase 2 4 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 4 0 1 2)
   (check-phase 3 6 0 1 2)
   ;; -- phase: the two auto cells
   (check-phase 3 1 0 0 0)
   (check-phase 3 5 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 1 1 1 0)
   (check-phase 2 1 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)))
