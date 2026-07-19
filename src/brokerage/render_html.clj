(ns brokerage.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`brokerage.operation` -> `brokerage.governor` -> `brokerage.store`)
  through a scenario adapted from this repo's own `brokerage.sim` demo
  driver (`clojure -M:dev:run`, run and read BEFORE writing this file:
  its output was cross-checked against `brokerage.store/demo-data`'s
  real seeded ids -- account-1..account-6, order-1..order-4 -- and
  every disposition it prints (auto-commit / escalate+approve / HARD
  hold) matched the real governor/phase output, unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, which turned out to
  reference ids that don't exist in its own seed data. `brokerage.sim`
  is safe to mine directly for real scenario values), trimmed to a
  representative subset (one full intake->assess->screen->file->
  screen->execute lifecycle for account-1/order-1, all human-approved
  at each escalation, plus three distinct HARD-hold reasons) and
  rendered deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [brokerage.store :as store]
            [brokerage.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: account-1 clears intake (auto-commit, no
  capital risk), a jurisdiction broker-dealer disclosure assessment
  (ALWAYS escalates -- approved), a conflict-of-interest screen (clean;
  ALWAYS escalates -- approved), order-1 is filed against account-1
  (auto-commit, filing itself moves no capital), a suitability screen
  (clean; ALWAYS escalates -- approved), then order-1 is EXECUTED (a
  real trade on the client's behalf -- ALWAYS escalates on
  `:actuation/execute-trade`, at every phase -- approved by the human
  broker). Then three distinct HARD holds, none of which ever reach a
  human: account-2's jurisdiction assessment cites no official
  spec-basis (`:no-spec-basis`), order-2 is filed against account-3
  which was never activated (`:account-not-active`), and account-5
  carries an undisclosed conflict of interest
  (`:conflict-of-interest`). Returns the resulting store -- every field
  read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "a1-intake" {:op :account/intake :subject "account-1"
                               :patch {:id "account-1" :client "田中 一郎"}})

    (exec! actor "a1-assess" {:op :jurisdiction/assess :subject "account-1"})
    (approve! actor "a1-assess")

    (exec! actor "a1-conflict" {:op :conflict/screen :subject "account-1"})
    (approve! actor "a1-conflict")

    (exec! actor "o1-file" {:op :order/file :subject "order-1" :account-id "account-1"
                             :security-type :equity :risk-level :medium
                             :quantity 100 :price 500 :claimed-order-value 50000})

    (exec! actor "o1-suitability" {:op :suitability/screen :subject "order-1"})
    (approve! actor "o1-suitability")

    (exec! actor "o1-execute" {:op :trade/execute :subject "order-1"})
    (approve! actor "o1-execute")

    (exec! actor "a2-assess" {:op :jurisdiction/assess :subject "account-2" :no-spec? true})

    (exec! actor "o2-file" {:op :order/file :subject "order-2" :account-id "account-3"
                             :security-type :equity :risk-level :low
                             :quantity 50 :price 100 :claimed-order-value 5000})

    (exec! actor "a5-conflict" {:op :conflict/screen :subject "account-5"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-rejected (:t f)) "<span class=\"critical\">approval rejected</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- account-row [ledger {:keys [id client jurisdiction risk-profile status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc client) (esc jurisdiction) (esc (name (or risk-profile :n-a)))
          (esc (name (or status :n-a))) (status-cell ledger id)))

(defn- order-row [ledger {:keys [id account-id security-type risk-level quantity price
                                 claimed-order-value]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc account-id) (esc (name (or security-type :n-a)))
          (esc (name (or risk-level :n-a))) (esc quantity) (esc price)
          (esc claimed-order-value) (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `brokerage.governor`/`brokerage.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:account/intake</code></td><td><span class=\"ok\">auto-commit when clean, no capital risk</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis required</span></td></tr>"
   "        <tr><td><code>:conflict/screen</code></td><td><span class=\"warn\">ALWAYS human approval &middot; HARD-holds on any conflict-of-interest hit</span></td></tr>"
   "        <tr><td><code>:suitability/screen</code></td><td><span class=\"warn\">ALWAYS human approval &middot; HARD-holds on any unsuitable-order finding</span></td></tr>"
   "        <tr><td><code>:order/file</code></td><td><span class=\"ok\">auto-commit when clean, no capital risk &middot; account must be active</span></td></tr>"
   "        <tr><td><code>:trade/execute</code></td><td><span class=\"warn\">ALWAYS human approval (actuation) &middot; independent value recompute &middot; double-execution blocked</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        accounts (store/all-accounts db)
        orders (->> ["order-1" "order-2"]
                    (keep (partial store/order db)))
        account-rows (str/join "\n" (map (partial account-row ledger) accounts))
        order-rows (str/join "\n" (map (partial order-row ledger) orders))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6612 &middot; securities/commodity brokerage</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Security and commodity contracts brokerage (ISIC 6612) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · trade execution always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Client accounts</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>brokerage.store</code> via <code>brokerage.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Account</th><th>Client</th><th>Jurisdiction</th><th>Risk profile</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     account-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Orders</h2>\n"
     "    <p class=\"muted\">Only orders that actually cleared <code>:order/file</code> exist on record — a HARD-held filing attempt (e.g. against an inactive account) never creates an order.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Order</th><th>Account</th><th>Security</th><th>Risk level</th><th>Qty</th><th>Price</th><th>Claimed value</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     order-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Brokerage Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Trade values are independently recomputed, never trusted from the proposal; executing a real trade on a client's behalf always requires a licensed broker's approval, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and hold this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/execution-history db)) "trade executions )")))
