(ns brokerage.brokerllm
  "Broker-LLM client -- the *contained intelligence node* for the
  securities/commodity brokerage actor.

  It normalizes account intake, drafts a per-jurisdiction broker-dealer
  registration/disclosure checklist, screens accounts for a conflict-
  of-interest signal, screens orders for a suitability signal,
  normalizes order filing, and drafts the trade-execution action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real trade execution. Every output is censored
  downstream by `brokerage.governor` before anything touches the SSoT,
  and `:trade/execute` proposals NEVER auto-commit at any phase -- see
  README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/execute-trade | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [brokerage.facts :as facts]
            [brokerage.registry :as registry]
            [brokerage.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the client, risk profile/investment objective or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "口座記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :account/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction broker-dealer registration/disclosure checklist
  draft. `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `brokerage.facts` -- the Brokerage Governor must reject this
  (never invent a jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/account db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "brokerage.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(def default-corporate-intel-screen
  "No-op corporate-intelligence cross-reference: always 'nothing on file'.
  This is the default so every existing caller of `screen-conflict`/
  `infer`/`mock-advisor` keeps its exact prior behavior unless it
  explicitly wires in `brokerage.corporate-intel/screen` (or an
  equivalent). Not required from this namespace directly -- keeping the
  dependency optional at the brokerllm level, injected only by whoever
  builds the advisor."
  (constantly {:found? false :hit? false}))

(defn- screen-conflict
  "Conflict-of-interest screening draft. `:conflict-hit?` on the
  account record injects the failure mode: the Brokerage Governor must
  HOLD, un-overridably, on any conflict-of-interest hit.

  `screen-fn` (client name -> corporate-intel result, see
  `brokerage.corporate-intel/screen`) is consulted ONLY once the local
  check is otherwise clean -- it can turn a would-be :clear into :hit or
  :incomplete, but a local conflict-of-interest hit is decided first,
  cheaply, without depending on an external actor at all. If a client is
  themselves a sanctioned/PEP entity, that is itself a conflict-of-
  interest-adjacent regulatory concern this local-only check alone would
  otherwise miss."
  [db {:keys [subject]} screen-fn]
  (let [a (store/account db subject)]
    (cond
      (nil? a)
      {:summary "対象accountが見つかりません" :rationale "no account record"
       :cites [] :effect :conflict/set :value {:account-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:conflict-hit? a)
      {:summary    (str (:client a) ": 利益相反を検出")
       :rationale  "スクリーニングが未開示の利益相反を検出。人手確認とホールドが必須。"
       :cites      [:conflict-check]
       :effect     :conflict/set
       :value      {:account-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      :else
      (let [ci (screen-fn (:client a))]
        (cond
          (:hit? ci)
          {:summary    (str (:client a) ": corporate-intelligence 照会で制裁/PEPフラグを検出")
           :rationale  "cloud-itonami-isic-8291 の名前スクリーニングが一致を検出。人手確認とホールドが必須。"
           :cites      [:conflict-check :corporate-intelligence]
           :effect     :conflict/set
           :value      {:account-id subject :verdict :hit}
           :stake      nil
           :confidence 0.9}

          (:pending-human-review? ci)
          {:summary    (str (:client a) ": corporate-intelligence 照会が人手レビュー待ち")
           :rationale  "cloud-itonami-isic-8291 側の DisclosureGovernor が high-stakes escalate 中。確定するまでクリアにできない。"
           :cites      [:conflict-check :corporate-intelligence]
           :effect     :conflict/set
           :value      {:account-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.5}

          (:held? ci)
          {:summary    (str (:client a) ": corporate-intelligence 照会が拒否された(契約/設定の問題)")
           :rationale  (str "cloud-itonami-isic-8291 の DisclosureGovernor が本テナントの照会を拒否: " (pr-str (:reason ci)))
           :cites      [:conflict-check :corporate-intelligence]
           :effect     :conflict/set
           :value      {:account-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.4}

          :else
          {:summary    (str (:client a) ": 利益相反なし")
           :rationale  "利益相反スクリーニング非該当。"
           :cites      [:conflict-check :corporate-intelligence]
           :effect     :conflict/set
           :value      {:account-id subject :verdict :clear}
           :stake      nil
           :confidence 0.9})))))

(defn- screen-suitability
  "Suitability screening draft -- checks the order's own `:risk-level`
  against the account's own `:risk-profile` via `registry/suitable-
  for-account?`. Injects the failure mode: the Brokerage Governor must
  HOLD, un-overridably, on any unsuitable order."
  [db {:keys [subject]}]
  (let [o (store/order db subject)
        a (when o (store/account db (:account-id o)))
        suitable? (and o a (registry/suitable-for-account? a o))]
    (cond
      (or (nil? o) (nil? a))
      {:summary "対象orderまたはaccountが見つかりません" :rationale "no order/account record"
       :cites [] :effect :suitability/set :value {:order-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (not suitable?)
      {:summary    (str subject ": リスク許容度に不適合")
       :rationale  (str "account risk-profile=" (:risk-profile a) " order risk-level=" (:risk-level o))
       :cites      [:suitability-check]
       :effect     :suitability/set
       :value      {:order-id subject :verdict :unsuitable}
       :stake      nil
       :confidence 0.9}

      :else
      {:summary    (str subject ": リスク許容度に適合")
       :rationale  (str "account risk-profile=" (:risk-profile a) " order risk-level=" (:risk-level o))
       :cites      [:suitability-check]
       :effect     :suitability/set
       :value      {:order-id subject :verdict :suitable}
       :stake      nil
       :confidence 0.9})))

(defn- propose-order-filing
  "Directory upsert for a new order -- the LLM only normalizes/
  validates the filed order's fields (account-id, security-type, risk-
  level, quantity, price, claimed-order-value); it does not invent
  them. High confidence, low stakes -- filing itself moves no capital,
  unlike executing it."
  [_db {:keys [subject account-id security-type risk-level quantity price claimed-order-value]}]
  {:summary    (str subject " (account " account-id ") の注文を受付")
   :rationale  "入力された注文事実の正規化のみ。新規事実の生成なし。"
   :cites      [:account-id :quantity :price]
   :effect     :order/filed
   :value      {:id subject :account-id account-id :security-type security-type
               :risk-level risk-level :quantity quantity :price price
               :claimed-order-value claimed-order-value :status :filed}
   :stake      nil
   :confidence 0.95})

(defn- propose-trade-execution
  "Draft the actual trade-EXECUTION action -- executing a real
  securities/commodity trade on a client's behalf. ALWAYS `:stake
  :actuation/execute-trade` -- this is a REAL-WORLD act (the client
  becomes bound to the trade), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`brokerage.phase`); the governor also always escalates
  on `:actuation/execute-trade`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [o (store/order db subject)
        recomputed (when o (registry/compute-order-value (:quantity o) (:price o)))
        matches? (and o recomputed
                      (< (Math/abs (- (double recomputed) (double (:claimed-order-value o)))) 0.01))]
    {:summary    (str subject " 向け約定提案"
                      (when o (str " (claimed=" (:claimed-order-value o) ")")))
     :rationale  (if o
                   (str "quantity=" (:quantity o) " price=" (:price o) " recomputed=" recomputed)
                   "orderが見つかりません")
     :cites      (if o [(:account-id o)] [])
     :effect     :order/mark-executed
     :value      {:order-id subject}
     :stake      :actuation/execute-trade
     :confidence (if matches? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}
  `screen-fn` (default: `default-corporate-intel-screen`, a no-op) is only
  consulted by `:conflict/screen`, once the local check is otherwise
  clean."
  ([db request] (infer db request default-corporate-intel-screen))
  ([db {:keys [op] :as request} screen-fn]
   (case op
     :account/intake         (normalize-intake db request)
     :jurisdiction/assess      (assess-jurisdiction db request)
     :conflict/screen           (screen-conflict db request screen-fn)
     :suitability/screen        (screen-suitability db request)
     :order/file                 (propose-order-filing db request)
     :trade/execute               (propose-trade-execution db request)
     {:summary "未対応の操作" :rationale (str op) :cites []
      :effect :noop :stake nil :confidence 0.0})))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere.
  opts:
    :corporate-intel-screen -- client name -> corporate-intel result (see
      `brokerage.corporate-intel/screen`). Default: no-op (never changes a
      screen-conflict verdict), so `(mock-advisor)` with no args keeps every
      existing caller's exact prior behavior."
  ([] (mock-advisor {}))
  ([{:keys [corporate-intel-screen]
     :or   {corporate-intel-screen default-corporate-intel-screen}}]
   (reify Advisor (-advise [_ st req] (infer st req corporate-intel-screen)))))

(def ^:private system-prompt
  (str "あなたは証券・商品先物ブローカレッジの発注・約定エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:account/upsert|:assessment/set|:conflict/set|:suitability/set|"
       ":order/filed|:order/mark-executed) "
       ":stake(:actuation/execute-trade か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:account (store/account st subject)}
    :conflict/screen      {:account (store/account st subject)}
    :suitability/screen   {:order (store/order st subject)}
    :trade/execute        {:order (store/order st subject)}
    {:account (store/account st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Brokerage Governor
  escalates/holds -- an LLM hiccup can never auto-execute a trade."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :brokerllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
