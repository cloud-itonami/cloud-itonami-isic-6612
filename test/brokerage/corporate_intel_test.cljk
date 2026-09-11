(ns brokerage.corporate-intel-test
  "Proves the value `brokerage.corporate-intel` actually adds: an account
  whose `:client` is clean on every LOCAL field (`:conflict-hit? false`)
  but whose client NAME is flagged in `cloud-itonami-isic-8291`'s own
  demo data no longer silently clears -- something this actor's local-
  only `:conflict-hit?` self-declaration alone would have missed entirely
  (see `account-6` in `brokerage.store/demo-data`: `:client` is
  \"Jane Smith (demo)\", a director of 8291's own sanctions-flagged demo
  `co-200`).

  Note: `:conflict/screen` NEVER auto-commits at any phase (see
  `brokerage.phase` -- it is absent from every phase's `:auto` set, the
  same posture `:suitability/screen` has) -- every scenario below that
  reaches `:commit` does so via an explicit approve, same as every other
  `:conflict/screen` test in `governor_contract_test.clj`. Only a HARD
  violation (a local `:conflict-hit?`, or a stubbed definitive corporate-
  intel `:hit? true`) settles immediately with no interrupt at all --
  8291's OWN real hits always escalate for 8291's own human review first
  (no shortcut, no peeking behind its DisclosureGovernor), so the end-to-
  end proof here is 'no longer silently clears', not 'now hard-holds' --
  see `corporate-intel-catches-the-hit-local-checks-miss` below."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [brokerage.store :as store]
            [brokerage.operation :as op]
            [brokerage.brokerllm :as brokerllm]
            [brokerage.corporate-intel :as ci]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- wired-actor []
  (let [db (store/seed-db)]
    [db (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-screen ci/screen})})]))

(deftest local-checks-alone-would-miss-the-8291-flagged-account
  (testing "sanity: without the integration wired in, account-6 passes every local check and clears"
    (let [db (store/seed-db)
          actor (op/build db)                          ; NO corporate-intel wired in
          res (exec-op actor "sanity" {:op :conflict/screen :subject "account-6"} operator)]
      (is (= :interrupted (:status res)) "conflict/screen always escalates for approval, clean or not")
      (approve! actor "sanity")
      (is (= :clear (:verdict (store/conflict-of db "account-6")))
          "without the integration, account-6 screens :clear -- this is the gap being closed"))))

(deftest corporate-intel-catches-the-hit-local-checks-miss
  (testing "with the REAL (unmocked) 8291 actor wired in, account-6 no longer silently clears"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t1" {:op :conflict/screen :subject "account-6"} operator)]
      (is (= :interrupted (:status res))
          "8291 itself escalates a real hit (Jane Smith (demo) is a director of its
           sanctions-flagged co-200, :reason :high-stakes on 8291's OWN side) for ITS
           OWN human review first -- 6612 never peeks behind that gate, so this also
           reads as :incomplete + low confidence here")
      (is (= :low-confidence (-> res :state :audit last :reason)))
      (approve! actor "t1")
      (is (not= :clear (:verdict (store/conflict-of db "account-6")))
          "critically: it never becomes :clear, unlike the unwired sanity case above")
      (is (= :incomplete (:verdict (store/conflict-of db "account-6"))))))
  (testing "the underlying reason: 8291 itself escalates this name because it is high-stakes"
    (let [result (ci/screen "Jane Smith (demo)")]
      (is (true? (:pending-human-review? result)))
      (is (= :high-stakes (:reason result))))))

(deftest corporate-intel-definitive-hit-hard-holds
  (testing "screen-conflict's :hit? branch itself is a HARD, un-overridable hold -- proven
            directly with a stub (a real 8291 hit always escalates for 8291's own human
            first, so this branch is only reachable end-to-end after that human confirms;
            unit-testing it here keeps the assertion deterministic, isolated from 8291's
            real timing)"
    (let [db (store/seed-db)
          definitive-hit (fn [_client] {:found? true :hit? true :capacity :director :org "co-x"})
          actor (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-screen definitive-hit})})
          res (exec-op actor "t2" {:op :conflict/screen :subject "account-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (some #{:conflict-of-interest} (-> (store/ledger db) first :basis)))
      (is (nil? (store/conflict-of db "account-6")) "no clearance written"))))

(deftest corporate-intel-held-screen-degrades-to-incomplete-not-clear
  (testing "if 6612's own tenant contract with 8291 is missing/misconfigured, 8291 itself
            holds the screen -- 6612 must treat that as inconclusive (escalate), never as
            clear"
    (let [db (store/seed-db)
          broken-screen (fn [_client] {:held? true :reason [:licensed-disclosure]})
          actor (op/build db {:advisor (brokerllm/mock-advisor {:corporate-intel-screen broken-screen})})
          res (exec-op actor "t3" {:op :conflict/screen :subject "account-6"} operator)]
      (is (= :interrupted (:status res)) "low confidence (:incomplete) -> escalate, same as a stubbed real hit")
      (is (nil? (store/conflict-of db "account-6")))
      (approve! actor "t3")
      (is (= :incomplete (:verdict (store/conflict-of db "account-6"))))
      (is (not= :clear (:verdict (store/conflict-of db "account-6")))))))

(deftest corporate-intel-clean-account-still-clears
  (testing "an account with no local signal, and no match in 8291's demo data, still clears --
            additive, not stricter-by-default (a confident not-found is not treated as a hit)"
    (let [[db actor] (wired-actor)
          res (exec-op actor "t4" {:op :conflict/screen :subject "account-1"} operator)]
      (is (= :interrupted (:status res)))
      (approve! actor "t4")
      (is (= :clear (:verdict (store/conflict-of db "account-1")))))))
