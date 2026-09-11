(ns brokerage.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:trade/execute` must NEVER be a member of any phase's
  `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [brokerage.phase :as phase]))

(deftest trade-execute-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real trade execution"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :trade/execute))
          (str "phase " n " must not auto-commit :trade/execute")))))

(deftest conflict-screen-never-auto-at-any-phase
  (testing "screening moves no capital, but is still never auto-eligible, matching every sibling KYC/conflict screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :conflict/screen))
          (str "phase " n " must not auto-commit :conflict/screen")))))

(deftest suitability-screen-never-auto-at-any-phase
  (testing "screening moves no capital, but is still never auto-eligible"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :suitability/screen))
          (str "phase " n " must not auto-commit :suitability/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":account/intake and :order/file move no capital -- auto-eligible"
    (is (= #{:account/intake :order/file} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :account/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :trade/execute} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :account/intake} :commit)))))
