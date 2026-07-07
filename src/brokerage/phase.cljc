(ns brokerage.phase
  "Phase 0->3 staged rollout -- the securities-brokerage analog of
  `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- account intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + conflict/
                                 suitability screening writes, still
                                 approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:account/intake`/`:order/file` (no
                                 capital risk yet) may auto-commit.
                                 `:trade/execute` NEVER auto-commits, at
                                 any phase.

  `:trade/execute` is deliberately ABSENT from every phase's `:auto`
  set, including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Executing a real trade on a client's behalf
  is the ONE real-world legal/financial act this actor performs; it is
  always a human broker's call. `brokerage.governor`'s `:actuation/
  execute-trade` high-stakes gate enforces the same invariant
  independently -- two layers, not one, agree on this. `:account/
  intake`/`:order/file` move no capital yet (still HARD-gated in
  `brokerage.governor`, but never `high-stakes`), so both ARE auto-
  eligible at phase 3, the same multi-auto-op posture `cloud-itonami-
  isic-6512`'s `casualty.phase` already establishes. `:conflict/screen`/
  `:suitability/screen` are never auto-eligible either, at any phase --
  the same posture every sibling's KYC/conflict screening op has, even
  though screening itself moves no capital.")

(def read-ops  #{})
(def write-ops #{:account/intake :jurisdiction/assess :conflict/screen :suitability/screen
                 :order/file :trade/execute})

;; NOTE the invariant: `:trade/execute` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                                          :auto #{}}
   1 {:label "assisted-intake" :writes #{:account/intake}                                                           :auto #{}}
   2 {:label "assisted-assess" :writes #{:account/intake :jurisdiction/assess :conflict/screen :suitability/screen} :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:account/intake :order/file}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:trade/execute` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Brokerage Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
