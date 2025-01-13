(ns monkey.ci.gui.admin.invoicing.views
  (:require [monkey.ci.gui.admin.search :as as]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]))

(defn page []
  [l/default
   [:<>
    [:h3 "Invoicing"]
    [as/search-customers
     {:goto-path #(r/path-for :admin/cust-invoices {:customer-id %})
      :init-view [:p.card-text "Search for a customer to view or manage their invoices."]}]
    #_[:div.card
     [:div.card-body
      ]]]])

