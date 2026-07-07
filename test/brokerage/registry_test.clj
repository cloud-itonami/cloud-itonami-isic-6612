(ns brokerage.registry-test
  (:require [clojure.test :refer [deftest is]]
            [brokerage.registry :as r]))

;; ----------------------------- compute-order-value -----------------------------

(deftest order-value-is-quantity-times-price
  (is (= 50000.0 (r/compute-order-value 100 500)))
  (is (= 0.0 (r/compute-order-value 0 500))))

(deftest compute-order-value-validation-rules
  (is (thrown? Exception (r/compute-order-value -1 500)))
  (is (thrown? Exception (r/compute-order-value 100 -1))))

;; ----------------------------- suitable-for-account? -----------------------------

(deftest conservative-accounts-only-hold-low-risk-orders
  (is (r/suitable-for-account? {:risk-profile :conservative} {:risk-level :low}))
  (is (not (r/suitable-for-account? {:risk-profile :conservative} {:risk-level :medium})))
  (is (not (r/suitable-for-account? {:risk-profile :conservative} {:risk-level :high}))))

(deftest moderate-accounts-hold-low-and-medium-risk-orders
  (is (r/suitable-for-account? {:risk-profile :moderate} {:risk-level :low}))
  (is (r/suitable-for-account? {:risk-profile :moderate} {:risk-level :medium}))
  (is (not (r/suitable-for-account? {:risk-profile :moderate} {:risk-level :high}))))

(deftest aggressive-accounts-hold-any-risk-order
  (is (r/suitable-for-account? {:risk-profile :aggressive} {:risk-level :low}))
  (is (r/suitable-for-account? {:risk-profile :aggressive} {:risk-level :medium}))
  (is (r/suitable-for-account? {:risk-profile :aggressive} {:risk-level :high})))

;; ----------------------------- register-trade-execution -----------------------------

(deftest trade-execution-is-a-draft-not-a-real-execution
  (let [result (r/register-trade-execution "account-1" "order-1" 100 500 50000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest trade-execution-assigns-trade-number
  (let [result (r/register-trade-execution "account-1" "order-1" 100 500 50000 "JPN" 7)]
    (is (= (get result "trade_number") "JPN-TRD-000007"))
    (is (= (get-in result ["record" "account_id"]) "account-1"))
    (is (= (get-in result ["record" "order_id"]) "order-1"))
    (is (= (get-in result ["record" "executed_value"]) 50000))
    (is (= (get-in result ["record" "kind"]) "trade-execution-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest trade-execution-validation-rules
  (is (thrown? Exception (r/register-trade-execution "" "order-1" 100 500 50000 "JPN" 0)))
  (is (thrown? Exception (r/register-trade-execution "account-1" "" 100 500 50000 "JPN" 0)))
  (is (thrown? Exception (r/register-trade-execution "account-1" "order-1" -1 500 50000 "JPN" 0)))
  (is (thrown? Exception (r/register-trade-execution "account-1" "order-1" 100 500 -1 "JPN" 0)))
  (is (thrown? Exception (r/register-trade-execution "account-1" "order-1" 100 500 50000 "" 0)))
  (is (thrown? Exception (r/register-trade-execution "account-1" "order-1" 100 500 50000 "JPN" -1))))

(deftest execution-history-is-append-only
  (let [e1 (r/register-trade-execution "account-1" "order-1" 100 500 50000 "JPN" 0)
        hist (r/append [] e1)
        e2 (r/register-trade-execution "account-1" "order-2" 10 1000 10000 "JPN" 1)
        hist2 (r/append hist e2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TRD-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TRD-000001" (get-in hist2 [1 "record_id"])))))
