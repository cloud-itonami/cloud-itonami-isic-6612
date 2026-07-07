(ns brokerage.store
  "SSoT for the securities/commodity brokerage actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/brokerage/store_contract_test.clj), which is the whole point:
  the actor, the Brokerage Governor and the audit ledger never know
  which SSoT they run on.

  The ledger stays append-only on every backend: 'which trade was
  executed for which account on what jurisdictional basis, which
  account was screened for conflict of interest, which order was
  screened for suitability, approved by whom' is always a query over an
  immutable log -- the audit trail a client trusting a broker with a
  trade needs, and the evidence an operator needs if an execution is
  later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [brokerage.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (account [s id])
  (all-accounts [s])
  (order [s id])
  (conflict-of [s account-id] "committed conflict-of-interest screening verdict for an account, or nil")
  (suitability-of [s order-id] "committed suitability screening verdict for an order, or nil")
  (assessment-of [s account-id] "committed jurisdiction registration/disclosure assessment, or nil")
  (ledger [s])
  (execution-history [s] "the append-only trade-execution history (brokerage.registry drafts)")
  (next-sequence [s jurisdiction] "next trade-number sequence for a jurisdiction")
  (order-already-executed? [s order-id] "has this order already been executed?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-accounts [s accounts] "replace/seed the account directory (map id->account)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained account set so the actor + tests run
  offline."
  []
  {:accounts
   {"account-1" {:id "account-1" :client "田中 一郎" :risk-profile :moderate
                 :investment-objective :growth :conflict-hit? false :disclosure-doc "form-jp-****1234"
                 :jurisdiction "JPN" :status :active}
    "account-2" {:id "account-2" :client "J. Smith" :risk-profile :moderate
                 :investment-objective :growth :conflict-hit? false :disclosure-doc "form-us-****5678"
                 :jurisdiction "ATL" :status :active}
    "account-3" {:id "account-3" :client "鈴木 花子" :risk-profile :moderate
                 :investment-objective :income :conflict-hit? false :disclosure-doc nil
                 :jurisdiction "JPN" :status :intake}
    "account-4" {:id "account-4" :client "佐藤 次郎" :risk-profile :conservative
                 :investment-objective :income :conflict-hit? false :disclosure-doc "form-jp-****9012"
                 :jurisdiction "JPN" :status :active}
    "account-5" {:id "account-5" :client "J. Doe" :risk-profile :moderate
                 :investment-objective :growth :conflict-hit? true :disclosure-doc nil
                 :jurisdiction "JPN" :status :active}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- execute-trade!
  "Backend-agnostic `:order/mark-executed` -- looks up the order + its
  account via the protocol, INDEPENDENTLY recomputes the order's
  notional value via `registry/compute-order-value` (never trusts the
  order's own claimed value -- the governor has already verified they
  match within tolerance, but the authoritative record persisted is
  always this vehicle's own math, the same discipline `auxiliary.
  store`/`reinsurance.store`/`realty.store` use), and returns
  {:result .. :order-patch ..} for the caller to persist."
  [s order-id]
  (let [o (order s order-id)
        a (account s (:account-id o))
        recomputed (registry/compute-order-value (:quantity o) (:price o))
        seq-n (next-sequence s (:jurisdiction a))
        result (registry/register-trade-execution
                (:account-id o) order-id (:quantity o) (:price o) recomputed (:jurisdiction a) seq-n)]
    {:result result
     :order-patch {:status :executed
                  :trade-number (get result "trade_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (account [_ id] (get-in @a [:accounts id]))
  (all-accounts [_] (sort-by :id (vals (:accounts @a))))
  (order [_ id] (get-in @a [:orders id]))
  (conflict-of [_ id] (get-in @a [:conflicts id]))
  (suitability-of [_ id] (get-in @a [:suitability id]))
  (assessment-of [_ account-id] (get-in @a [:assessments account-id]))
  (ledger [_] (:ledger @a))
  (execution-history [_] (:executions @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (order-already-executed? [_ order-id] (= :executed (get-in @a [:orders order-id :status])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :account/upsert
      (swap! a update-in [:accounts (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :conflict/set
      (swap! a assoc-in [:conflicts (first path)] payload)

      :suitability/set
      (swap! a assoc-in [:suitability (first path)] payload)

      :order/filed
      (swap! a assoc-in [:orders (:id payload)] payload)

      :order/mark-executed
      (let [order-id (first path)
            {:keys [result order-patch]} (execute-trade! s order-id)
            jurisdiction (:jurisdiction (account s (:account-id (order s order-id))))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:orders order-id] merge order-patch)
                       (update :executions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-accounts [s accounts] (when (seq accounts) (swap! a assoc :accounts accounts)) s))

(defn seed-db
  "A MemStore seeded with the demo account set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :conflicts {} :suitability {} :ledger [] :sequences {}
                           :orders {} :executions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/conflict/suitability payloads, ledger
  facts, execution records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:account/id                 {:db/unique :db.unique/identity}
   :order/id                   {:db/unique :db.unique/identity}
   :assessment/account-id      {:db/unique :db.unique/identity}
   :conflict/account-id        {:db/unique :db.unique/identity}
   :suitability/order-id       {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :execution/seq              {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- account->tx [{:keys [id client risk-profile investment-objective conflict-hit? disclosure-doc jurisdiction status]}]
  (cond-> {:account/id id}
    client                    (assoc :account/client client)
    risk-profile              (assoc :account/risk-profile risk-profile)
    investment-objective      (assoc :account/investment-objective investment-objective)
    (some? conflict-hit?)     (assoc :account/conflict-hit? conflict-hit?)
    disclosure-doc            (assoc :account/disclosure-doc disclosure-doc)
    jurisdiction              (assoc :account/jurisdiction jurisdiction)
    status                    (assoc :account/status status)))

(def ^:private account-pull
  [:account/id :account/client :account/risk-profile :account/investment-objective
   :account/conflict-hit? :account/disclosure-doc :account/jurisdiction :account/status])

(defn- pull->account [m]
  (when (:account/id m)
    {:id (:account/id m) :client (:account/client m) :risk-profile (:account/risk-profile m)
     :investment-objective (:account/investment-objective m)
     :conflict-hit? (boolean (:account/conflict-hit? m)) :disclosure-doc (:account/disclosure-doc m)
     :jurisdiction (:account/jurisdiction m) :status (:account/status m)}))

(defn- order->tx [{:keys [id account-id security-type risk-level quantity price
                        claimed-order-value status trade-number]}]
  (cond-> {:order/id id}
    account-id           (assoc :order/account-id account-id)
    security-type         (assoc :order/security-type security-type)
    risk-level             (assoc :order/risk-level risk-level)
    quantity                (assoc :order/quantity quantity)
    price                    (assoc :order/price price)
    claimed-order-value     (assoc :order/claimed-order-value claimed-order-value)
    status                   (assoc :order/status status)
    trade-number             (assoc :order/trade-number trade-number)))

(def ^:private order-pull
  [:order/id :order/account-id :order/security-type :order/risk-level :order/quantity
   :order/price :order/claimed-order-value :order/status :order/trade-number])

(defn- pull->order [m]
  (when (:order/id m)
    {:id (:order/id m) :account-id (:order/account-id m) :security-type (:order/security-type m)
     :risk-level (:order/risk-level m) :quantity (:order/quantity m) :price (:order/price m)
     :claimed-order-value (:order/claimed-order-value m)
     :status (:order/status m) :trade-number (:order/trade-number m)}))

(defrecord DatomicStore [conn]
  Store
  (account [_ id]
    (pull->account (d/pull (d/db conn) account-pull [:account/id id])))
  (all-accounts [_]
    (->> (d/q '[:find [?id ...] :where [?e :account/id ?id]] (d/db conn))
         (map #(pull->account (d/pull (d/db conn) account-pull [:account/id %])))
         (sort-by :id)))
  (order [_ id]
    (pull->order (d/pull (d/db conn) order-pull [:order/id id])))
  (conflict-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :conflict/account-id ?aid] [?k :conflict/payload ?p]]
              (d/db conn) id)))
  (suitability-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?oid
                :where [?k :suitability/order-id ?oid] [?k :suitability/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ account-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/account-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) account-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (execution-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :execution/seq ?s] [?e :execution/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (order-already-executed? [s order-id]
    (= :executed (:status (order s order-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :account/upsert
      (d/transact! conn [(account->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/account-id (first path) :assessment/payload (enc payload)}])

      :conflict/set
      (d/transact! conn [{:conflict/account-id (first path) :conflict/payload (enc payload)}])

      :suitability/set
      (d/transact! conn [{:suitability/order-id (first path) :suitability/payload (enc payload)}])

      :order/filed
      (d/transact! conn [(order->tx payload)])

      :order/mark-executed
      (let [order-id (first path)
            {:keys [result order-patch]} (execute-trade! s order-id)
            jurisdiction (:jurisdiction (account s (:account-id (order s order-id))))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(order->tx (assoc order-patch :id order-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:execution/seq (count (execution-history s)) :execution/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-accounts [s accounts]
    (when (seq accounts) (d/transact! conn (mapv account->tx (vals accounts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data` ({:accounts
  ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [accounts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-accounts s accounts))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo account set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
