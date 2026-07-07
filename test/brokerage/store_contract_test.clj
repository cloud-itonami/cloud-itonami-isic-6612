(ns brokerage.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [brokerage.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "田中 一郎" (:client (store/account s "account-1"))))
      (is (= "JPN" (:jurisdiction (store/account s "account-1"))))
      (is (= :moderate (:risk-profile (store/account s "account-1"))))
      (is (false? (:conflict-hit? (store/account s "account-1"))))
      (is (true? (:conflict-hit? (store/account s "account-5"))))
      (is (= ["account-1" "account-2" "account-3" "account-4" "account-5"]
             (mapv :id (store/all-accounts s))))
      (is (nil? (store/order s "order-1")))
      (is (nil? (store/conflict-of s "account-1")))
      (is (nil? (store/suitability-of s "order-1")))
      (is (nil? (store/assessment-of s "account-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/execution-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/order-already-executed? s "order-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :account/upsert
                                 :value {:id "account-1" :investment-objective :income}})
        (is (= :income (:investment-objective (store/account s "account-1"))))
        (is (= "田中 一郎" (:client (store/account s "account-1"))) "client preserved"))
      (testing "assessment / conflict / suitability payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["account-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "account-1")))
        (store/commit-record! s {:effect :conflict/set :path ["account-1"]
                                 :payload {:account-id "account-1" :verdict :clear}})
        (is (= {:account-id "account-1" :verdict :clear} (store/conflict-of s "account-1")))
        (store/commit-record! s {:effect :suitability/set :path ["order-1"]
                                 :payload {:order-id "order-1" :verdict :suitable}})
        (is (= {:order-id "order-1" :verdict :suitable} (store/suitability-of s "order-1"))))
      (testing "order filing writes a plain order record (no draft/certificate -- filing moves no capital)"
        (store/commit-record! s {:effect :order/filed
                                 :payload {:id "order-1" :account-id "account-1" :security-type :equity
                                          :risk-level :medium :quantity 100 :price 500
                                          :claimed-order-value 50000 :status :filed}})
        (is (= :filed (:status (store/order s "order-1"))))
        (is (= 100 (:quantity (store/order s "order-1")))))
      (testing "trade execution drafts an execution record with THIS actor's own recomputed value, not the claimed one, and advances the sequence"
        (store/commit-record! s {:effect :order/mark-executed :path ["order-1"]})
        (is (= "JPN-TRD-000000" (get (first (store/execution-history s)) "record_id")))
        (is (= "trade-execution-draft" (get (first (store/execution-history s)) "kind")))
        (is (= 50000.0 (get (first (store/execution-history s)) "executed_value"))
            "100 x 500 recomputed independently, matching the (correct) claimed value here")
        (is (= :executed (:status (store/order s "order-1"))))
        (is (= 1 (count (store/execution-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/order-already-executed? s "order-1")))
        (is (false? (store/order-already-executed? s "order-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/account s "nope")))
    (is (= [] (store/all-accounts s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/execution-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-accounts s {"x" {:id "x" :client "c" :risk-profile :moderate
                                 :investment-objective :growth :conflict-hit? false
                                 :disclosure-doc nil :jurisdiction "JPN" :status :active}})
    (is (= "c" (:client (store/account s "x"))))))
