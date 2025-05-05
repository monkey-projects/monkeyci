(ns monkey.ci.gui.admin.invoicing.views
  (:require [monkey.ci.gui.admin.search :as as]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn page []
  [l/default
   [:<>
    [:h3 "Invoicing"]
    [as/search-orgs
     {:get-route #(vector :admin/cust-invoices {:org-id (:id %)})
      :init-view [:p.card-text "Search for an organization to view or manage its invoices."]}]]])

(defn- title []
  (let [cust (rf/subscribe [:org/info])]
    [:h3 (:name @cust) ": Invoices"]))

(defn org-invoices [route]
  (rf/dispatch [:org/maybe-load (r/org-id route)])
  (fn [route]
    [l/default
     [:<>
      [title]
      [:div.card
       [:div.card-body
        [:p "TODO Invoice overview"]]]]]))
