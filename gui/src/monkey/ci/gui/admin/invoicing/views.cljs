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

(defn- title []
  (let [org (rf/subscribe [:org/info])]
    [:div.d-flex
     [co/page-title "Invoice overview for " [:b (:name @org)]]
     [:div.ms-auto
      [co/reload-btn-sm [::e/load (:id @org)]]]]))

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

(defn- new-invoice-btn []
  [:button.btn.btn-primary
   {:on-click (u/link-evt-handler [::e/new])}
   [co/icon-text :file-earmark-plus "New Invoice"]])

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
          [:div.mt-2
           [invoice-table]]
          [new-invoice-btn]]]]])))
