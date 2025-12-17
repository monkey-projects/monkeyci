(ns monkey.ci.gui.admin.invoicing.views
  (:require [monkey.ci.gui.admin.search :as as]
            [monkey.ci.gui.admin.invoicing.events :as e]
            [monkey.ci.gui.admin.invoicing.subs :as s]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn page []
  [l/default
   [:<>
    [:h3 "Invoicing"]
    [as/search-orgs
     {:get-route #(vector :admin/org-invoices {:org-id (:id %)})
      :init-view [:p.card-text "Search for an organization to view or manage its invoices."]}]]])

(defn- new-invoice-btn [org-id]
  [:a.btn.btn-primary
   {:href (r/path-for :admin/invoice-new {:org-id org-id})}
   [co/icon-text :file-earmark-plus "New Invoice"]])

(defn- title []
  (let [org (rf/subscribe [:org/info])
        id (:id @org)]
    [:div.d-flex
     [:div.flex-grow-1
      [co/page-title "Invoice overview for " [:b (:name @org)]
       [:span.ms-2 [co/reload-btn-sm [::e/load id]]]]]
     [new-invoice-btn id]]))

(defn invoice-table []
  [t/paged-table
   {:id ::invoices
    :items-sub [::s/invoices]
    :columns [{:label "Invoice"
               :value :invoice-nr}
              {:label "Date"
               :value :date}
              {:label "Amount"
               :value :net-amount}]
    :loading {:sub [::s/loading?]}}])

(defn org-invoices [route]
  (let [org-id (-> route r/path-params :org-id)]
    (rf/dispatch [:org/maybe-load org-id])
    (rf/dispatch [::e/load org-id])
    (fn [route]
      [l/default
       [:<>
        [title]
        [a/component [::s/alerts]]
        [:div.card
         [:div.card-body
          [invoice-table]]]]])))

(defn new-invoice [_]
  [l/default
   [co/page-title [co/icon-text :file-earmark-plus "New Invoice"]]])
