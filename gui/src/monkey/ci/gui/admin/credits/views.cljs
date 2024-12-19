(ns monkey.ci.gui.admin.credits.views
  (:require [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.admin.credits.events]))

(defn search-customer-form []
  [:div.bg-primary-dark.overflow-hidden
   [:div.container.position-relative.content-space-1
    ;; Search form, does nothing for now
    [:div.w-lg-75.mx-lg-auto
     [:form#customer-search
      [:div.input-card
       [:div.input-card-form
        [:label.form-label.visually-hidden {:for :customer-filter}
         "Search for customer"]
        [:input.form-control {:type :text
                              :name :customer-filter
                              :id :customer-filter
                              :placeholder "Search for customer"
                              :aria-label "Search for customer"}]]
       [:button.btn.btn-primary.btn-icon
        {:type :submit
         :on-click (f/submit-handler [:credits/customer-search] :customer-search)}
        [:i.bi-search]]]]]]])

(defn overview []
  [l/default
   [:<>
    [:h3 "Credit Management"]
    [:div.mt-3
     [search-customer-form]]
    [:div.card
     [:div.card-body
      [:p.card-text "Search for a customer to view their credit overview."]]]]])
