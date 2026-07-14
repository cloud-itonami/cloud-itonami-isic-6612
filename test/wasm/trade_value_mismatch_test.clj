(ns wasm.trade-value-mismatch-test
  "Hosts wasm/trade_value_mismatch.wasm (compiled from
  wasm/trade_value_mismatch.kotoba, see wasm/README.md) via
  kototama.tender -- proves brokerage.governor's
  `trade-value-mismatch-violations` independent-recompute cross-check
  (`brokerage.registry/compute-order-value` vs. an order's OWN claimed
  value, the `:trade-value-mismatch` HARD violation) runs as a real WASM
  guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the three real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/trade_value_mismatch.kotoba's ns-adjacent header comment for the
  offset layout and the x100 fixed-point scaling convention (the SAME
  scale factor `brokerage.governor/value->x100` uses)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/trade_value_mismatch.wasm"))))

(defn- run-trade-value-matches?
  [quantity price-x100 claimed-value-x100]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 quantity)
    (.writeI32 memory 4 price-x100)
    (.writeI32 memory 8 claimed-value-x100)
    (tender/call-main instance)))

(deftest trade-value-wasm-approves-exact-match
  (testing "quantity=100, price-x100=50000 ($500.00/unit) -> recomputed value-x100 = 5,000,000 ($50,000.00), matches the claim -> approves"
    ;; hand-verified against brokerage.sim's own fixture (100 * 500 =
    ;; 50000 -> value->x100 5000000): 100 * 50000 = 5,000,000
    (is (= 1 (run-trade-value-matches? 100 50000 5000000)))))

(deftest trade-value-wasm-rejects-clear-mismatch
  (testing "quantity=50, price-x100=10000 ($100.00/unit) -> recomputed value-x100 = 500,000 ($5,000.00), but claimed is 600,000 ($6,000.00) -> rejects"
    (is (= 0 (run-trade-value-matches? 50 10000 600000)))))

(deftest trade-value-wasm-rejects-one-cent-mismatch
  (testing "boundary: recomputed 1,000,000 ($10,000.00) vs claimed 1,000,001 ($10,000.01) -- off by exactly one cent -> rejects (in-kernel tolerance is 1 cent, i.e. exact match required)"
    (is (= 0 (run-trade-value-matches? 10 100000 1000001)))
    (is (= 1 (run-trade-value-matches? 10 100000 1000000)))))

(deftest trade-value-wasm-handles-zero-quantity
  (testing "zero quantity -> recomputed value-x100 is 0 (not a crash); matches a 0 claim, rejects a nonzero claim"
    (is (= 1 (run-trade-value-matches? 0 50000 0)))
    (is (= 0 (run-trade-value-matches? 0 50000 1)))))

(deftest trade-value-wasm-approves-fractional-price
  (testing "quantity=7, price-x100=1999 ($19.99/unit) -> recomputed value-x100 = 13,993 ($139.93), matches the claim -> approves"
    ;; hand-verified: 7 * $19.99 = $139.93 -> x100 = 13993; fixed-point:
    ;; 7 * 1999 = 13993
    (is (= 1 (run-trade-value-matches? 7 1999 13993)))))
