(ns brokerage.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. the reserved read code and unknown, all governor
     dispositions). The façade delegates, so this is the guard that
     delegation didn't change semantics.
  3. governor boundary — the confidence floor boundary, the
     fail-closed treatment of out-of-range confidence, and the
     trade-value tolerance parity against the old |diff| < 0.01
     predicate, exercised through the real `brokerage.governor/check`
     façade and representative values."
  (:require [brokerage.governor :as governor]
            [brokerage.kernels.gate :as gate]
            [clojure.test :refer [deftest is testing]]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

(deftest value-tolerance-pinned-to-old-facade-tolerance
  (is (= gate/value-tolerance-x100 (Math/round (* 100.0 0.01)))
      "the kernel's 1-cent tolerance is the x100 restatement of the old |recomputed - claimed| < 0.01")
  (testing "kernel x100 comparison == the old close? predicate over representative values"
    (doseq [[r c] [[50000.0 50000.0]              ; exact match
                   [50000.0 50001.0]              ; whole-unit mismatch
                   [1014.9999999999999 1015.0]    ; float noise from quantity*price
                   [0.0 0.0]
                   [123.45 123.45]]]
      (is (= (< (Math/abs (- (double r) (double c))) 0.01)
             (= 0 (gate/value-mismatch (Math/round (* 100.0 (double r)))
                                       (Math/round (* 100.0 (double c))))))
          (str "tolerance divergence at recomputed=" r " claimed=" c)))))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. This
;; domain's read-ops set is EMPTY (there are no read ops), so the
;; read pass-through branch is restated as vacuously dead — op 0 is
;; reserved and must fail closed like an unknown write.

(def ^:private ref-read-ops #{})
(def ^:private ref-phases
  {0 {:writes #{}              :auto #{}}
   1 {:writes #{1}             :auto #{}}
   2 {:writes #{1 2 3 4}       :auto #{}}
   3 {:writes #{1 2 3 4 5 6}   :auto #{1 5}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (216 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5 6 7]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest trade-execute-auto-enabled-nowhere
  (testing "op 6 (:trade/execute) — and ops 3/4 (:conflict/screen,
            :suitability/screen), matching every sibling screening op —
            are auto-enabled at NO phase: the kernel restates the phase
            table's permanent structural invariant"
    (doseq [phase [-1 0 1 2 3 4 7]
            op    [3 4 6]]
      (is (= 0 (gate/op-auto-enabled phase op))
          (str "op " op " must not be auto-enabled at phase " phase)))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :account/intake
;; touches neither the store nor the spec/evidence/order/value
;; checks, so the verdict is decided purely by confidence/actuation —
;; nil store is safe.

(defn- verdict [proposal]
  (governor/check {:op :account/intake :subject "account-x"} {} proposal nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/execute-trade}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :jurisdiction/assess :subject "account-x"} {}
                            {:confidence 0.99 :stake :actuation/execute-trade :cites []} nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:no-spec-basis} (mapv :rule (:violations v)))))))
