(ns brokerage.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean account through
  intake -> jurisdiction broker-dealer disclosure assessment ->
  conflict-of-interest screening -> order filing (auto-commits; no
  capital risk) -> suitability screening -> trade-execution proposal
  (always escalates) -> human approval -> commit, then shows seven HARD
  holds (a jurisdiction with no spec-basis, an order filed against an
  inactive account, an account with an undisclosed conflict of
  interest, an order whose risk level does not match the client's risk
  tolerance, a trade value that doesn't match this actor's own
  independent recompute, a nonexistent order, and a double-execution of
  an already-executed order) that never reach a human at all, and
  prints the audit ledger + the draft trade-execution records."
  (:require [langgraph.graph :as g]
            [brokerage.store :as store]
            [brokerage.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== account/intake account-1 (JPN, moderate risk profile, clean) ==")
    (println (exec! actor "t1" {:op :account/intake :subject "account-1"
                                :patch {:id "account-1" :client "田中 一郎"}} operator))

    (println "== jurisdiction/assess account-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "account-1"} operator))
    (println (approve! actor "t2"))

    (println "== conflict/screen account-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :conflict/screen :subject "account-1"} operator))
    (println (approve! actor "t3"))

    (println "== order/file order-1 against account-1 (active; 100 shares x 500 = 50,000 correct; auto-commits, no capital risk) ==")
    (println (exec! actor "t4" {:op :order/file :subject "order-1" :account-id "account-1"
                                :security-type :equity :risk-level :medium
                                :quantity 100 :price 500 :claimed-order-value 50000} operator))

    (println "== suitability/screen order-1 (moderate account, medium risk -- clean; escalates -- human approves) ==")
    (println (exec! actor "t5" {:op :suitability/screen :subject "order-1"} operator))
    (println (approve! actor "t5"))

    (println "== trade/execute order-1 (always escalates -- actuation/execute-trade) ==")
    (let [r (exec! actor "t6" {:op :trade/execute :subject "order-1"} operator)]
      (println r)
      (println "-- human broker approves --")
      (println (approve! actor "t6")))

    (println "== jurisdiction/assess account-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "account-2" :no-spec? true} operator))

    (println "== order/file order-2 against account-3 (never active -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :order/file :subject "order-2" :account-id "account-3"
                                :security-type :equity :risk-level :low
                                :quantity 50 :price 100 :claimed-order-value 5000} operator))

    (println "== conflict/screen account-5 (undisclosed conflict of interest -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :conflict/screen :subject "account-5"} operator))

    (println "== order/file order-3 against account-4 (conservative account, high-risk commodity order; filing itself auto-commits) ==")
    (println (exec! actor "t10a" {:op :order/file :subject "order-3" :account-id "account-4"
                                  :security-type :commodity :risk-level :high
                                  :quantity 10 :price 1000 :claimed-order-value 10000} operator))

    (println "== suitability/screen order-3 (high-risk order does not match conservative risk profile -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :suitability/screen :subject "order-3"} operator))

    (println "== order/file order-4 against account-1 (100 shares x 500 = 50,000; 60,000 WRONGLY claimed; filing itself auto-commits) ==")
    (println (exec! actor "t11a" {:op :order/file :subject "order-4" :account-id "account-1"
                                  :security-type :equity :risk-level :medium
                                  :quantity 100 :price 500 :claimed-order-value 60000} operator))

    (println "== trade/execute order-4 (claimed value does not match this actor's own recompute -> HARD hold) ==")
    (println (exec! actor "t11" {:op :trade/execute :subject "order-4"} operator))

    (println "== trade/execute order-999 (nonexistent order -> HARD hold) ==")
    (println (exec! actor "t12" {:op :trade/execute :subject "order-999"} operator))

    (println "== trade/execute order-1 AGAIN (double-execution of an already-executed order -> HARD hold) ==")
    (println (exec! actor "t13" {:op :trade/execute :subject "order-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft trade-execution records ==")
    (doseq [r (store/execution-history db)] (println r))))
