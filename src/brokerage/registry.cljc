(ns brokerage.registry
  "Pure-function trade-execution record construction -- an append-only
  brokerage book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a trade-execution reference number -- every
  broker-dealer/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `brokerage.facts` uses.

  `compute-order-value` is a REAL, trivial formula (quantity times
  price), not an invented placeholder default -- see its own docstring
  for the honest simplification it makes vs. a real trade's full terms
  (no commissions, no exchange fees, no multi-leg orders, no partial
  fills). This is the SAME 'reimplement the well-known math
  independently, so a downstream governor can cross-check a claimed
  figure against it' pattern `cloud-itonami-isic-6629`'s/`6520`'s/
  `6820`'s checks establish -- applied here to a FIFTH domain-specific
  formula, as an EXACT-MATCH check (a trade has exactly one correct
  notional value).

  `suitable-for-account?` is a REAL, simplified risk-tolerance-matching
  rule (FINRA Rule 2111 / MiFID II Art. 25 both require a suitability
  match between a recommendation's risk level and a customer's risk
  tolerance, in spirit) -- see its own docstring for what it does not
  model (no concentration limits, no time-horizon/liquidity-needs
  analysis, no product-complexity tiering).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any exchange/clearing system. It builds the RECORD a broker-
  dealer would keep, not the act of executing the trade itself (that is
  `brokerage.operation`'s `:trade/execute`, always human-gated -- see
  README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed broker-dealer's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn compute-order-value
  "Pure computation of a trade order's notional value -- a REAL,
  trivial formula (see ns docstring for what it does not model: no
  commissions, no exchange fees, no multi-leg orders, no partial
  fills): value = quantity * price."
  [quantity price]
  (when (neg? quantity)
    (throw (ex-info "compute-order-value: quantity must be >= 0" {})))
  (when (neg? price)
    (throw (ex-info "compute-order-value: price must be >= 0" {})))
  (* (double quantity) (double price)))

(def ^:private risk-tolerance
  "account risk-profile -> the set of order risk-levels it may hold. A
  REAL, simplified suitability-matching table (see ns docstring for
  what a full suitability analysis additionally considers)."
  {:conservative #{:low}
   :moderate     #{:low :medium}
   :aggressive   #{:low :medium :high}})

(defn suitable-for-account?
  "Does `order`'s `:risk-level` fall within `account`'s `:risk-profile`
  tolerance? A REAL, simplified risk-tolerance-matching rule -- see ns
  docstring for what it does not model (concentration limits, time-
  horizon/liquidity-needs analysis, product-complexity tiering)."
  [account order]
  (contains? (get risk-tolerance (:risk-profile account) #{}) (:risk-level order)))

(defn register-trade-execution
  "Validate + construct the TRADE-EXECUTION registration DRAFT -- the
  broker-dealer's own legal act of executing a real securities/
  commodity trade on a client's behalf. Pure function -- does not touch
  any real exchange/clearing system; it builds the RECORD a broker-
  dealer would keep. `brokerage.governor` independently re-verifies the
  order's claimed value against `compute-order-value`, checks
  suitability against `suitable-for-account?`, and blocks a double-
  execution of the same order, before this is ever allowed to commit."
  [account-id order-id quantity price executed-value jurisdiction sequence]
  (when-not (and account-id (not= account-id ""))
    (throw (ex-info "trade-execution: account_id required" {})))
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "trade-execution: order_id required" {})))
  (when (neg? quantity)
    (throw (ex-info "trade-execution: quantity must be >= 0" {})))
  (when (neg? executed-value)
    (throw (ex-info "trade-execution: executed-value must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "trade-execution: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "trade-execution: sequence must be >= 0" {})))
  (let [trade-number (str (str/upper-case jurisdiction) "-TRD-" (zero-pad sequence 6))
        record {"record_id" trade-number
                "kind" "trade-execution-draft"
                "account_id" account-id
                "order_id" order-id
                "quantity" quantity
                "price" price
                "executed_value" executed-value
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "trade_number" trade-number
     "certificate" (unsigned-certificate "TradeExecutionCertificate" trade-number trade-number)}))

(defn append
  "Append a trade-execution record, returning a NEW list (never mutate
  history in place)."
  [history result]
  (conj (vec history) (get result "record")))
