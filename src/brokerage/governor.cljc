(ns brokerage.governor
  "Brokerage Governor -- the independent compliance layer that earns
  the Broker-LLM the right to commit. The LLM has no notion of
  jurisdictional broker-dealer registration/disclosure law, whether an
  account is actually active before an order is filed, whether a
  claimed trade value actually matches quantity times price, whether an
  order's risk level actually matches the client's own risk tolerance,
  or when an act stops being a draft and becomes a real-world trade
  execution, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the securities-brokerage analog of
  `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Six checks, in priority order. The first five are HARD violations: a
  human approver CANNOT override them (you don't get to approve your
  way past a fabricated jurisdiction spec-basis, incomplete broker-
  dealer disclosure evidence, an order filed against an inactive
  account, a nonexistent order, an undisclosed conflict of interest, or
  an unsuitable order). The sixth, trade-value mismatch, is ALSO HARD.
  The confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `brokerage.phase`: for `:stake :actuation/execute-trade` (a real
  trade on a client's behalf) NO phase ever allows auto-commit either.
  Two independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`brokerage.
                                       facts`), or invent one? Applies
                                       to `:trade/execute` ONLY when the
                                       order actually exists (see
                                       `pension.governor`'s/`realty.
                                       governor`'s own ADR-0001s for the
                                       lesson this proactively avoids).
    2. Evidence incomplete         -- for `:trade/execute`, are the
                                       jurisdiction's required KYC/
                                       suitability/disclosure docs
                                       actually satisfied for the
                                       order's own account?
    3. Account not active          -- for `:order/file`, has the
                                       referenced account actually been
                                       activated? An order cannot be
                                       filed against an account that
                                       was never taken on.
    4. Order missing               -- for `:trade/execute`, does the
                                       referenced order actually exist
                                       on file?
    5. Conflict of interest        -- does THIS proposal itself report
                                       a conflict-of-interest hit (a
                                       `:conflict/screen` that just
                                       found one), or does the account
                                       already carry one on file?
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`
                                       established and `adjustment.
                                       governor`/`intermediation.
                                       governor`'s conflict-of-interest
                                       checks reused -- scoping this to
                                       `:trade/execute` alone would
                                       silently prevent the screening op
                                       (`:conflict/screen`) from ever
                                       HARD-holding on its own finding.
    6. Suitability failure         -- does THIS proposal itself report
                                       an unsuitable order (a
                                       `:suitability/screen` that just
                                       found one), or does the order
                                       already carry an unsuitable
                                       verdict on file? Evaluated
                                       UNCONDITIONALLY, the SAME
                                       discipline as check 5, applied to
                                       a DIFFERENT screening concern
                                       (risk-tolerance matching, not
                                       identity/relationship).
    7. Trade-value mismatch        -- for `:trade/execute`, does the
                                       order's OWN claimed value
                                       actually match `brokerage.
                                       registry/compute-order-value`'s
                                       independent recompute (quantity
                                       times price)? Never trusts a
                                       claimed figure as-is -- the SAME
                                       'independently re-derive, never
                                       trust a claimed number' discipline
                                       `cloud-itonami-isic-6629`'s/
                                       `6520`'s/`6820`'s checks apply.
    8. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:trade/execute`
                                       (a REAL legal/financial act) ->
                                       escalate.

  One more guard, double-execution prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream/
  account comparison at all -- `double-execution-violations` refuses to
  execute the SAME order twice, off this actor's own execution
  history."
  (:require [brokerage.facts :as facts]
            [brokerage.kernels.gate :as gate]
            [brokerage.registry :as registry]
            [brokerage.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `brokerage.kernels.gate/confidence-floor-x100` (integer x100 in the
  safety kernel); this def is kept for callers/docs and pinned equal
  by `brokerage.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Executing a real trade on a client's behalf is the ONE real-world
  actuation event this actor performs -- a single-member set, matching
  `cloud-itonami-isic-6511`'s/`6621`'s/`6629`'s single-actuation shape,
  not `6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s dual-actuation one."
  #{:actuation/execute-trade})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

(defn- value->x100
  "Host bridge (façade-side, not kernel vocabulary): round a monetary
  order value to the kernel's x100 (cent) integer wire value. Rounding
  both sides to cents before the in-kernel 1-cent tolerance matches
  the old |recomputed − claimed| < 0.01 predicate on cent-exact values
  and absorbs float noise the same way."
  [v]
  (Math/round (* 100.0 (double v))))

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:trade/execute`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's broker-dealer registration/disclosure requirements.
  For `:trade/execute`, only applies when the order actually exists."
  [{:keys [op subject]} proposal st]
  (when (contains? #{:jurisdiction/assess :trade/execute} op)
    (when (or (not= op :trade/execute) (store/order st subject))
      (let [value (:value proposal)]
        (when (or (empty? (:cites proposal))
                  (and (contains? value :spec-basis) (nil? (:spec-basis value))))
          [{:rule :no-spec-basis
            :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}])))))

(defn- evidence-incomplete-violations
  "For `:trade/execute`, the jurisdiction's required KYC/suitability/
  disclosure evidence must actually be satisfied for the order's own
  account -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (= op :trade/execute)
    (when-let [o (store/order st subject)]
      (let [a (store/account st (:account-id o))
            assessment (store/assessment-of st (:account-id o))]
        (when-not (and assessment
                       (facts/required-evidence-satisfied?
                        (:jurisdiction a) (:checklist assessment)))
          [{:rule :evidence-incomplete
            :detail "法域の必要書類(口座開設申込書/適合性確認書等)が充足していない状態での約定提案"}])))))

(defn- account-not-active-violations
  "For `:order/file`, the referenced account must actually be
  `:status :active` -- an order cannot be filed against an account
  that was never taken on. A brokerage account's status never
  regresses out of `:active` once entered, so checking `:status`
  directly here carries none of `cloud-itonami-isic-6622`'s status-
  lifecycle risk."
  [{:keys [op account-id]} st]
  (when (= op :order/file)
    (when-not (= :active (:status (store/account st account-id)))
      [{:rule :account-not-active
        :detail (str account-id " は有効化(active)されていないため、注文は受理できない")}])))

(defn- order-missing-violations
  "For `:trade/execute`, the referenced order must actually exist on
  file -- refuses to execute a fabricated/nonexistent order id."
  [{:keys [op subject]} st]
  (when (= op :trade/execute)
    (when-not (store/order st subject)
      [{:rule :order-missing
        :detail (str subject " という注文は登録されていない")}])))

(defn- conflict-of-interest-violations
  "A conflict-of-interest hit -- reported by THIS proposal (e.g. a
  `:conflict/screen` that itself just found one), or already on file
  in the store for the account (`:trade/execute`) -- is a HARD,
  un-overridable hold. Evaluated UNCONDITIONALLY (not scoped to a
  specific op) so the screening op itself can HARD-hold on its own
  finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        account-id (cond
                     (= op :conflict/screen) subject
                     (= op :trade/execute) (:account-id (store/order st subject)))
        hit-on-file? (and account-id (= :hit (:verdict (store/conflict-of st account-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :conflict-of-interest
        :detail "未開示の利益相反のある口座を含む提案は進められない"}])))

(defn- suitability-failure-violations
  "An unsuitable-order finding -- reported by THIS proposal (e.g. a
  `:suitability/screen` that itself just found one), or already on
  file in the store for the order (`:trade/execute`) -- is a HARD,
  un-overridable hold. Evaluated UNCONDITIONALLY, the SAME discipline
  as `conflict-of-interest-violations`, applied to a DIFFERENT
  screening concern (risk-tolerance matching, not identity/
  relationship)."
  [{:keys [op subject]} proposal st]
  (let [fail-in-proposal? (= :unsuitable (get-in proposal [:value :verdict]))
        order-id (cond
                   (= op :suitability/screen) subject
                   (= op :trade/execute) subject)
        fail-on-file? (and order-id (= :unsuitable (:verdict (store/suitability-of st order-id))))]
    (when (or fail-in-proposal? fail-on-file?)
      [{:rule :suitability-failure
        :detail "顧客のリスク許容度に適合しない注文を含む提案は進められない"}])))

(defn- trade-value-mismatch-violations
  "For `:trade/execute`, INDEPENDENTLY recompute the order's notional
  value via `brokerage.registry/compute-order-value` and compare
  against the order's OWN claimed value -- never trusts a claimed
  figure as-is. The DECIDING comparison is in-kernel
  (`gate/value-mismatch` over x100 cent integers against
  `gate/value-tolerance-x100`, the integer restatement of the old
  |recomputed - claimed| < 0.01); this façade keeps the
  human-readable evidence."
  [{:keys [op subject]} st]
  (when (= op :trade/execute)
    (when-let [o (store/order st subject)]
      (let [recomputed (registry/compute-order-value (:quantity o) (:price o))]
        (when (= 1 (gate/value-mismatch (value->x100 recomputed)
                                        (value->x100 (:claimed-order-value o))))
          [{:rule :trade-value-mismatch
            :detail (str subject " の申告額(" (:claimed-order-value o)
                        ")が独自再計算値(" recomputed ")と一致しない")}])))))

(defn- double-execution-violations
  "For `:trade/execute`, refuses to execute the SAME order twice, off
  this actor's own execution history -- needs no upstream/account
  comparison at all."
  [{:keys [op subject]} st]
  (when (= op :trade/execute)
    (when (store/order-already-executed? st subject)
      [{:rule :double-execution
        :detail (str subject " は既に約定済み")}])))

(defn check
  "Censors a Broker-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [spec-v (spec-basis-violations request proposal st)
        evid-v (evidence-incomplete-violations request st)
        acct-v (account-not-active-violations request st)
        miss-v (order-missing-violations request st)
        coi-v  (conflict-of-interest-violations request proposal st)
        suit-v (suitability-failure-violations request proposal st)
        val-v  (trade-value-mismatch-violations request st)
        dbl-v  (double-execution-violations request st)
        hard (into [] (concat spec-v evid-v acct-v miss-v coi-v suit-v val-v dbl-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        o (when (= (:op request) :trade/execute)
            (store/order st (:subject request)))
        ;; The decision itself is delegated to the safety kernel
        ;; (brokerage.kernels.gate, integer-coded fail-closed core);
        ;; this façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (if (seq spec-v) 1 0)
                                (if (seq evid-v) 1 0)
                                (if (seq acct-v) 1 0)
                                (if (seq miss-v) 1 0)
                                (if (seq coi-v) 1 0)
                                (if (seq suit-v) 1 0)
                                (if o 1 0)
                                (if o (value->x100 (registry/compute-order-value
                                                    (:quantity o) (:price o)))
                                      0)
                                (if o (value->x100 (:claimed-order-value o)) 0)
                                (if (seq dbl-v) 1 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
