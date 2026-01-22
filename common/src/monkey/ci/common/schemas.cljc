(ns monkey.ci.common.schemas
  "Prismatic schemas, used by both backend and frontend"
  (:require [schema.core :as s]))

(def not-empty-str (s/constrained s/Str not-empty))
(def Id not-empty-str)
(def Name not-empty-str)

(defn- assoc-id [s]
  (assoc s (s/optional-key :id) Id))

(s/defschema Label
  {:name Name
   :value not-empty-str})

(s/defschema NewRepo
  {:org-id Id
   :name s/Str
   :url s/Str
   (s/optional-key :main-branch) s/Str
   (s/optional-key :public) s/Bool
   (s/optional-key :github-id) (s/maybe s/Int)
   (s/optional-key :labels) [Label]})

(s/defschema UpdateRepo
  (assoc-id NewRepo))

(s/defschema Mailing
  {:subject s/Str
   (s/optional-key :text-body) s/Str
   (s/optional-key :html-body) s/Str})

(s/defschema SentMailing
  {:to-users s/Bool
   :to-subscribers s/Bool
   :other-dests [s/Str]})

(s/defschema EmailUnregistrationQuery
  {(s/optional-key :id) Id
   (s/optional-key :email) s/Str
   (s/optional-key :user-id) Id})

(s/defschema UserCredits
  {:amount s/Int
   (s/optional-key :reason) s/Str
   (s/optional-key :valid-from) s/Int
   (s/optional-key :valid-until) s/Int})

(s/defschema AutoCredits
  ;; ISO date format
  {:date #"\d{4}-\d{2}-\d{2}"})

(def period-pattern #"^P(\d+Y)?(\d+M)?(\d+D)?$")

(s/defschema CreditSubscription
  {:amount s/Int
   :valid-from s/Int
   (s/optional-key :valid-until) s/Int
   (s/optional-key :description) s/Str
   (s/optional-key :valid-period) period-pattern})

(s/defschema DisableCreditSubscription
  {(s/optional-key :valid-until) s/Int})

(s/defschema UserSettings
  {:receive-mailing s/Bool})

(s/defschema InvoiceSearchFilter
  {(s/optional-key :from-date) s/Str
   (s/optional-key :until-date) s/Str
   (s/optional-key :invoice-nr) s/Str})

(def invoice-kind (s/enum :invoice :creditnote))

(s/defschema InvoiceDetail
  {:description s/Str
   :net-amount s/Num
   :vat-perc s/Num})

(def currencies ["EUR" "USD" "GBP"])

(s/defschema NewInvoice
  {:kind invoice-kind
   (s/optional-key :date) s/Int
   :details [InvoiceDetail]
   :net-amount s/Num
   :vat-perc s/Num
   :currency (apply s/enum currencies)})

(s/defschema UpdateInvoice
  (assoc NewInvoice :id Id))

(s/defschema OrgInvoicing
  {:currency s/Str
   :address [s/Str]
   :country s/Str
   (s/optional-key :vat-nr) s/Str})
