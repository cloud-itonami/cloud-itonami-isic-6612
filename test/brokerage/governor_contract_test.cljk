(ns brokerage.governor-contract-test
  "The governor contract as executable tests -- the securities-
  brokerage analog of `cloud-itonami-isic-6512`'s `casualty.governor-
  contract-test`. The single invariant under test:

    Broker-LLM never executes a trade the Brokerage Governor would
    reject, `:trade/execute` NEVER auto-commits at any phase,
    `:account/intake`/`:order/file` (no capital risk) MAY auto-commit
    when clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [brokerage.store :as store]
            [brokerage.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess-account1!
  "Walks account-1 through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject "account-1"} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- file-order!
  [actor tid order-id account-id risk-level quantity price claimed-order-value]
  (exec-op actor tid {:op :order/file :subject order-id :account-id account-id
                      :security-type :equity :risk-level risk-level
                      :quantity quantity :price price :claimed-order-value claimed-order-value} operator))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :account/intake :subject "account-1"
                   :patch {:id "account-1" :investment-objective :income}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :income (:investment-objective (store/account db "account-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "account-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "account-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "account-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "account-1")) "no assessment written"))))

(deftest order-file-against-inactive-account-is-held
  (testing "an order filed for a never-activated account -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (file-order! actor "t4" "order-1" "account-3" :low 50 100 5000)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:account-not-active} (-> (store/ledger db) first :basis)))
      (is (nil? (store/order db "order-1")) "no order written"))))

(deftest order-file-against-active-account-auto-commits
  (testing ":order/file moves no capital yet -- auto-eligible at phase 3, once the account is active"
    (let [[db actor] (fresh)
          res (file-order! actor "t5" "order-1" "account-1" :medium 100 500 50000)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :filed (:status (store/order db "order-1"))) "SSoT actually updated"))))

(deftest conflict-hit-is-held-and-unoverridable
  (testing "a conflict-of-interest hit on an account -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :conflict/screen :subject "account-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "account-5")) "no clearance written"))))

(deftest suitability-failure-is-held-and-unoverridable
  (testing "an order whose risk level does not match the account's risk profile -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (file-order! actor "t7pre" "order-1" "account-4" :high 10 1000 10000)
          res (exec-op actor "t7" {:op :suitability/screen :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:suitability-failure} (-> (store/ledger db) last :basis))
          "the order/file pre-step auto-commits first, so the suitability hold is the SECOND ledger entry")
      (is (nil? (store/suitability-of db "order-1")) "no clearance written"))))

(deftest trade-execute-without-assessment-is-held
  (testing "trade/execute before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          _ (file-order! actor "t8pre" "order-1" "account-1" :medium 100 500 50000)
          res (exec-op actor "t8" {:op :trade/execute :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) last :basis))))))

(deftest trade-execute-with-missing-order-is-held
  (testing "executing an order id that was never filed -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :trade/execute :subject "order-999"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:order-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/execution-history db))))))

(deftest trade-value-mismatch-is-held
  (testing "an order whose claimed value does not match this actor's own independent recompute -> HOLD"
    (let [[db actor] (fresh)
          _ (assess-account1! actor "t10pre")
          _ (file-order! actor "t10file" "order-1" "account-1" :medium 100 500 99999)
          res (exec-op actor "t10" {:op :trade/execute :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:trade-value-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/execution-history db))))))

(deftest trade-execute-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, correctly-valued trade still ALWAYS interrupts for human approval -- actuation/execute-trade is never auto"
    (let [[db actor] (fresh)
          _ (assess-account1! actor "t11pre")
          _ (file-order! actor "t11file" "order-1" "account-1" :medium 100 500 50000)
          r1 (exec-op actor "t11" {:op :trade/execute :subject "order-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, trade-execution record drafted"
        (let [r2 (approve! actor "t11")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :executed (:status (store/order db "order-1"))))
          (is (= 1 (count (store/execution-history db))) "one draft execution record")))))
  (testing "reject -> hold, nothing executed"
    (let [[db actor] (fresh)
          _ (assess-account1! actor "t12pre")
          _ (file-order! actor "t12file" "order-1" "account-1" :medium 100 500 50000)
          _ (exec-op actor "t12" {:op :trade/execute :subject "order-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t12" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/execution-history db)) "nothing executed on reject"))))

(deftest trade-execute-double-execution-is-held
  (testing "executing the same order twice -> HOLD on the second attempt, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (assess-account1! actor "t13pre")
          _ (file-order! actor "t13file" "order-1" "account-1" :medium 100 500 50000)
          _ (exec-op actor "t13a" {:op :trade/execute :subject "order-1"} operator)
          _ (approve! actor "t13a")
          res (exec-op actor "t13" {:op :trade/execute :subject "order-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-execution} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/execution-history db))) "still only the one earlier execution"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :account/intake :subject "account-1"
                          :patch {:id "account-1" :investment-objective :income}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "account-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
