(ns monkey.ci.gui.billing.views
  "Org billing overview.  This allows the user to choose their plan (free or commercial)
   and modify the org billing details."
  (:require [monkey.ci.common.schemas :as cs]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.org-settings.views :as settings]))

(defn- plans []
  [:div.card
   [:div.card-body
    [:h4.text-primary.card-title "Usage Plan"]
    [:p "This is still in development, please check again later."]]])

(defn- select [id lbl items v]
  [:<>
   [:label.form-label {:for id} lbl]
   (->> items
        (map (fn [[k v]]
               [:option {:value k} v]))
        (into [:select.form-select
               {:aria-label lbl
                :id id
                :name id}
               [:option]]))])

(defn- currency-select []
  [select :currency "Currency" (map (partial repeat 2) cs/currencies)])

(def countries [["BE" "Belgium"]])

(defn- country-select []
  [select :country "Country" countries])

(defn- billing-form []
  [:form
   [:div.row
    [:div.col.col-md-6
     [f/form-input {:id :vat-nr
                    :label "VAT Number"
                    :value ""
                    :help-msg "Only for organizations that are VAT mandatory"}]]]
   (->> (range 3)
        (map (fn [i]
               [:div.row.mt-2
                [:div.col
                 [f/form-input {:id (keyword (str "address-" i))
                                :label (str "Invoice Address " (inc i)) 
                                :value ""}]]]))
        (into [:<>]))
   [:div.row.mt-2
    [:div.col.col-md-6
     [country-select]]]
   [:div.row.mt-2
    [:div.col.col-md-4
     [currency-select]]]   
   [:div.row.mt-2.mt-md-4
    [:div.col.d-flex.gap-2
     [:button.btn.btn-primary [co/icon-text :floppy "Save"]]
     [co/cancel-btn]]]])

(defn- billing []
  [:div.card
   [:div.card-body
    [:h4.text-primary.card-title "Billing"]
    [:p "Configure billing details for your organization."]
    [billing-form]]])

(defn page [_]
  (settings/settings-page
   ::settings/billing
   [:<>
    [co/page-title "Usage Plan and Billing"]
    [:p
     "On this page you can choose your " [co/docs-link "articles/pricing" "usage plan"]
     " and edit your billing information. "
     "Note that " [:i "MonkeyCI"] " remains " [:b "free for non-commercial use"] " as long "
     "as you have not chosen a commercial plan. Entering billing information does not impact "
     "that. But we do " [:b "require your billing details"] " should you choose a commercial plan."]
    [:div.d-flex.flex-column.gap-2.gap-md-4
     [plans]
     [billing]]]))

