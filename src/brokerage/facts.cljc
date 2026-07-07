(ns brokerage.facts
  "Per-jurisdiction broker-dealer registration/disclosure regulatory
  catalog -- the G2-style spec-basis table the Brokerage Governor
  checks every jurisdiction/assess proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's broker-dealer
  registration/suitability-disclosure requirements, or did it invent
  one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official securities
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.

  Like `pension.facts`'s `USA` (not `USA-NY`), broker-dealer
  registration in the US IS federally regulated (Securities Exchange
  Act of 1934 + FINRA membership) -- unlike insurance/real-estate
  licensing, which is per-state -- so this catalog's US entry is `USA`,
  a genuine national authority, not a state exemplar.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  account-application/suitability/disclosure evidence set submitted in
  some form; `:legal-basis` / `:owner-authority` / `:provenance` are
  the G2 citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "金融商品取引法 (Financial Instruments and Exchange Act, FIEA)"
          :national-spec "日本証券業協会 適合性の原則に関する規則"
          :provenance "https://www.fsa.go.jp/"
          :required-evidence ["口座開設申込書 (account application/KYC form)"
                              "適合性確認書 (suitability questionnaire)"
                              "リスク開示書面 (risk disclosure statement)"
                              "外務員登録証明書 (broker registration/license certificate)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Securities and Exchange Commission (SEC) / Financial Industry Regulatory Authority (FINRA)"
          :legal-basis "Securities Exchange Act of 1934 §15(b) (broker-dealer registration) + FINRA Rule 2111 (suitability)"
          :national-spec "FINRA Rule 2090 (know your customer) + Rule 2111 (suitability)"
          :provenance "https://www.sec.gov/ https://www.finra.org/"
          :required-evidence ["Account application / KYC form"
                              "Suitability questionnaire"
                              "Risk disclosure statement"
                              "Broker registration / license certificate (Form U4)"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "FCA Conduct of Business Sourcebook (COBS) 9A (suitability)"
          :national-spec "FCA Handbook COBS 9A + Markets in Financial Instruments Directive II (MiFID II) implementation"
          :provenance "https://www.fca.org.uk/"
          :required-evidence ["Account application / KYC form"
                              "Suitability questionnaire"
                              "Risk disclosure statement"
                              "Broker registration / approved-person certificate"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Wertpapierhandelsgesetz (WpHG) §63 ff. (Geeignetheitsprüfung, MiFID II implementation)"
          :national-spec "BaFin Rundschreiben zur Geeignetheits- und Angemessenheitsprüfung"
          :provenance "https://www.bafin.de/"
          :required-evidence ["Kontoeröffnungsantrag (account application/KYC form)"
                              "Geeignetheitsfragebogen (suitability questionnaire)"
                              "Risikoaufklärung (risk disclosure statement)"
                              "Maklerzulassung (broker registration/license certificate)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to execute a trade
  on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6612 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `brokerage.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
