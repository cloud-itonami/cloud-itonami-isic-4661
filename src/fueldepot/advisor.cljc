(ns fueldepot.advisor
  "FuelDepotAdvisor -- the *contained intelligence node* for the ISIC
  4661 (wholesale of solid, liquid and gaseous fuels and related
  products) fuel-wholesale-depot operations coordination actor.

  It normalizes shipment-record patches (fuel-receipt/dispatch/
  inventory-level data), drafts a tanker-truck/pipeline delivery-
  operation scheduling proposal against a depot, drafts a safety-
  concern flag (leak/spill/tank-integrity concern), and drafts a
  supplier-procurement-order coordination proposal against a depot.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a real tank/pipeline-valve actuation,
  fuel-transfer control, or hazmat-storage-safety/tank-integrity
  clearance decision. Every output is censored downstream by
  `fueldepot.governor` before anything touches the SSoT -- see README
  `What this actor does NOT do`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `fueldepot.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:shipment-record/log
                                 ; :delivery-operation/schedule
                                 ; :safety-concern/flag
                                 ; :supplier-order/coordinate}
                                 ; propose-shaped effects, NEVER a
                                 ; direct tank/pipeline-control effect
                                 ; and NEVER a hazmat-storage-safety or
                                 ; tank/pipeline-integrity clearance
                                 ; decision
     :stake      kw|nil         ; :coordination/safety-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) --
  `fueldepot.governor` HARD-holds any request that doesn't, so a
  mis-wired caller can never reach a commit path even if this advisor
  were compromised.

  None of this advisor's default rationale text ever mentions
  finalizing a tank-integrity clearance, finalizing a hazmat-storage-
  safety clearance, or any bare safety-adjacent noun in a way that
  could collide with `fueldepot.governor`'s own scope-exclusion checks
  -- those checks are boolean-flag-based (see that ns's docstring), not
  text-search-based, and
  `fueldepot.governor-contract-test`'s
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion-
  checks` test asserts this directly."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [fueldepot.registry :as registry]
            [fueldepot.store :as store]
            [langchain.model :as model]))

(defn- log-shipment-record
  "Shipment-record intake upsert -- the advisor only normalizes/
  validates the patch; it does not invent the record's fuel-type,
  quantity, direction, or verification status. High confidence, low
  stakes -- administrative logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "出荷記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :shipment-record/log
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- schedule-delivery-operation
  "Draft a tanker-truck/pipeline delivery-operation scheduling proposal
  against a depot. The advisor reports what it can see (depot
  verified?/registered?) in its rationale, but `fueldepot.governor`
  NEVER trusts this report -- it independently re-derives verified?/
  registered? from the depot's own stored fields before any commit is
  possible."
  [db {:keys [subject value]}]
  (let [depot-id (:depot-id value)
        d (store/depot db depot-id)
        ready? (and d (registry/depot-ready? d))]
    {:summary    (str subject " 向け配送作業予定提案 (" (:mode value) ")"
                      (when d (str " depot=" depot-id)))
     :rationale  (if d
                   (str "depot-verified?=" (registry/depot-verified? d)
                        " depot-registered?=" (registry/depot-registered? d)
                        " quantity-liters=" (:quantity-liters value))
                   (str depot-id " が見つかりません"))
     :cites      (if d [depot-id] [])
     :effect     :delivery-operation/schedule
     :value      value
     :stake      nil
     :confidence (if ready? 0.9 0.3)}))

(defn- flag-safety-concern
  "Draft a leak/spill/tank-integrity concern report. ALWAYS `:stake
  :coordination/safety-concern` -- a safety concern is NEVER a proposal
  the advisor may quietly downgrade to low-stakes, and it is never
  gated on the referenced depot being verified (a concern can be raised
  about ANY depot, verified or not -- see README `What this actor does
  NOT do` re: never blocking safety-relevant reporting on an
  administrative technicality). See `fueldepot.phase`: no phase ever
  adds this op to a phase's `:auto` set; `fueldepot.governor` also
  always escalates on `:coordination/safety-concern`. Two independent
  layers agree, deliberately."
  [db {:keys [subject value]}]
  (let [depot-id (:depot-id value)
        d (and depot-id (store/depot db depot-id))]
    {:summary    (str subject " 向け安全懸念報告 (" (:severity value) ")"
                      (when d (str " depot=" depot-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if d [depot-id] [])
     :effect     :safety-concern/flag
     :value      value
     :stake      :coordination/safety-concern
     :confidence 0.9}))

(defn- coordinate-supplier-order
  "Draft a supplier fuel-procurement order coordination proposal
  against a depot. The advisor passes through the caller's own claimed
  order value -- it does NOT invent one, and `fueldepot.governor`
  NEVER trusts it: it independently re-derives whether the order's own
  claimed `:order-value-usd` would cross the cost-escalation threshold
  before deciding whether human sign-off is required."
  [db {:keys [subject value]}]
  (let [depot-id (:depot-id value)
        d (store/depot db depot-id)
        ready? (and d (registry/depot-ready? d))
        high-value? (registry/order-value-exceeds-threshold? (:order-value-usd value))]
    {:summary    (str subject " 向け仕入先発注調整提案 ("
                      (:order-value-usd value) " USD)"
                      (when d (str " depot=" depot-id)))
     :rationale  (if d
                   (str "depot-verified?=" (registry/depot-verified? d)
                        " depot-registered?=" (registry/depot-registered? d)
                        " high-value?=" high-value?)
                   (str depot-id " が見つかりません"))
     :cites      (if d [depot-id] [])
     :effect     :supplier-order/coordinate
     :value      value
     :stake      nil
     :confidence (if (and ready? (not high-value?)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-shipment-record        (log-shipment-record db request)
    :schedule-delivery-operation (schedule-delivery-operation db request)
    :flag-safety-concern        (flag-safety-concern db request)
    :coordinate-supplier-order  (coordinate-supplier-order db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは燃料卸売事業所(depot)運用コーディネーターの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:shipment-record/log|:delivery-operation/schedule|"
       ":safety-concern/flag|:supplier-order/coordinate) "
       ":stake(:coordination/safety-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録の事業所免許に対する配送作業や仕入先発注を"
       "提案してはいけません。タンク/パイプライン設備の直接操作(actuate)を"
       "絶対に提案してはいけません(この actor は提案のみを行い、実行は一切"
       "行いません)。タンク/パイプラインの完全性(integrity)クリアランスや"
       "危険物貯蔵安全(hazmat-storage-safety)クリアランスの確定を提案しては"
       "いけません(この actor は認証機関ではありません)。数量や発注額を"
       "偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-shipment-record        {:shipment-record (store/shipment-record st subject)}
    :schedule-delivery-operation {:depot (store/depot st (:depot-id value))}
    :flag-safety-concern         {:depot (and (:depot-id value) (store/depot st (:depot-id value)))}
    :coordinate-supplier-order   {:depot (store/depot st (:depot-id value))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `fueldepot.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a delivery,
  auto-flag a concern, or auto-coordinate a supplier order."
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
  {:t          :fueldepot-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
