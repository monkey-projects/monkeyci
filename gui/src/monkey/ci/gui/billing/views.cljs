(ns monkey.ci.gui.billing.views
  "Org billing overview.  This allows the user to choose their plan (free or commercial)
   and modify the org billing details."
  (:require [monkey.ci.common.schemas :as cs]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.billing.events :as e]
            [monkey.ci.gui.billing.subs :as s]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.countries :as countries]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.org-settings.views :as settings]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.template.plans :as p]
            [re-frame.core :as rf]))

(defn- plan-card [curr? {:keys [amount title summary features] :as plan}]
  [:div.card
   [:div.card-header.text-center
    [:div.fs-3.text-dark.fw-bold (if (pos? amount) [:span (str "€" amount) [:span.fs-6 "/user/mo"]] "Free")]
    [:h5.card-title title]
    [:p.card-text summary]]
   [:div.card-body.d-flex.justify-content-center.h-100.py-0
    (->> features
         (map (fn [f]
                [:li.list-checked-item f]))
         (into [:ul.list-checked.list-checked-primary]))]   
   [:div.card-footer.text-center
    (if curr?
      [:p "Your current plan"]
      [:button.btn.btn-primary
       {:on-click (u/link-evt-handler [::e/select-plan (:type plan)])}
       "Select Plan"])]])

(defn- plans []
  [:div.card
   [:div.card-body
    [:h4.text-primary.card-title "Usage Plan"]
    [:p "You can modify your usage plan by selecting a new plan below."]
    (->> p/plans
         (map-indexed (fn [i p] (plan-card (zero? i) p)))
         (into [:div.row.card-group]))]
   [:div.card-footer.text-center
    [:p.fs-6.fst-italic
     "* Paying plans allow you to add multiple users to your organizations, up to the "
     "number of users in your plan, or the maximum users allowed by the plan."]]])

(defn- select [id lbl items sel opts]
  [:<>
   [:label.form-label {:for id} lbl]
   (->> items
        (map (fn [[k v]]
               [:option
                (cond-> {:value k}
                  (= k sel) (assoc :selected true))
                v]))
        (into [:select.form-select
               (merge {:aria-label lbl
                       :id id
                       :name id}
                      opts)
               [:option]]))])

(defn- currency-select [v opts]
  [select :currency "Currency" (map (partial repeat 2) cs/currencies ) v opts])

(def countries (map (juxt :code :name) countries/countries))

(defn- country-select [v opts]
  [select :country "Country" countries v opts])

(defn- save-btn []
  (let [s? (rf/subscribe [::s/saving?])]
    [:button.btn.btn-primary
     (cond-> {:on-click (u/link-evt-handler [::e/save-invoicing])}
       @s? (assoc :disabled true))
     (if @s?
       [:span.me-2.spinner-border.spinner-border-sm]
       [:span.me-2 [co/icon :floppy]])
     "Save"]))

(defn- billing-form []
  (let [l? (rf/subscribe [::s/billing-loading?])
        v (rf/subscribe [::s/invoicing-settings])]
    [:form
     [:div.row
      [:div.col.col-md-6
       [f/form-input {:id :vat-nr
                      :label "VAT Number"
                      :value (:vat-nr @v)
                      :help-msg "Only for organizations that are VAT mandatory"
                      :extra-opts
                      {:on-change (u/form-evt-handler [::e/invoicing-settings-changed :vat-nr])
                       :disabled @l?}}]]]
     (->> (range 3)
          (map (fn [i]
                 [:div.row.mt-2
                  [:div.col
                   [f/form-input {:id (keyword (str "address-" i))
                                  :label (str "Invoice Address " (inc i)) 
                                  :value (get (:address @v) i)
                                  :extra-opts
                                  {:disabled @l?
                                   :on-change (u/form-evt-handler [::e/invoicing-address-changed i])}}]]]))
          (into [:<>]))
     [:div.row.mt-2
      [:div.col.col-md-6
       [country-select (:country @v)
        {:disabled @l?
         :on-change (u/form-evt-handler [::e/invoicing-settings-changed :country])}]]]
     [:div.row.mt-2
      [:div.col.col-md-4
       [currency-select (:currency @v)
        {:disabled @l?
         :on-change (u/form-evt-handler [::e/invoicing-settings-changed :currency])}]]]
     [:div.row.mt-2.mt-md-4
      [:div.col.d-flex.gap-2
       [save-btn]
       [co/cancel-btn]]]]))

(defn- billing []
  (rf/dispatch [::e/load-invoicing])
  (fn []
    [:div.card
     [:div.card-body
      [:h4.text-primary.card-title "Billing"]
      [:p.mb-2 "Configure billing details for your organization."]
      [a/component [::s/billing-alerts]]
      [billing-form]]]))

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

