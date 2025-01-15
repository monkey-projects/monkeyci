(ns monkey.ci.gui.admin.invoicing.views
  (:require [monkey.ci.gui.admin.search :as as]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn page []
  [l/default
   [:<>
    [:h3 "Invoicing"]
    [as/search-customers
     {:get-route #(vector :admin/cust-invoices {:customer-id %})
      :init-view [:p.card-text "Search for a customer to view or manage their invoices."]}]]])

(defn customer-invoices [route]
  (rf/dispatch [:customer/maybe-load (r/customer-id route)])
  (fn [route]
    (let [cust (rf/subscribe [:customer/info])]
      [l/default
       [:<>
        [:h3 (:name @cust) ": Invoices"]
        [:div.card
         [:div.card-body
          [:p "TODO Invoice overview"]]]]])))
