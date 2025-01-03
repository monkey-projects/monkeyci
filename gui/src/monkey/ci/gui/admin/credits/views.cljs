(ns monkey.ci.gui.admin.credits.views
  (:require [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.admin.credits.events]
            [monkey.ci.gui.admin.credits.subs]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [re-frame.core :as rf]))

(defn cust-search-btn []
  (let [l (rf/subscribe [:credits/customers-loading?])]
    [:button.btn.btn-primary.btn-icon
     {:type :submit
      :on-click (f/submit-handler [:credits/customer-search] :customer-search)
      :disabled @l}
     [:i.bi-search]]))

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
       [cust-search-btn]]]]]])

(defn- customers-table []
  (letfn [(render-id [{:keys [id]}]
            [:a {:href (r/path-for :admin/cust-credits {:customer-id id})} id])]
    [t/paged-table
     {:id ::customers
      :items-sub [:credits/customers]
      :columns (-> [{:label "Id"
                     :value render-id
                     :sorter (t/prop-sorter :id)}
                    {:label "Name"
                     :value :name
                     :sorter (t/prop-sorter :name)}]
                   (t/add-sorting 1 :desc))
      :loading {:sub [:credits/customers-loading?]}
      :on-row-click #(rf/dispatch [:route/goto :admin/cust-credits {:customer-id (:id %)}])}]))

(defn search-results []
  (let [loaded? (rf/subscribe [:credits/customers-loaded?])]
    [:div.card
     [:div.card-body
      (if @loaded?
        [customers-table]
        [:p.card-text "Search for a customer to view their credit overview."])]]))

(defn overview []
  [l/default
   [:<>
    [:h3 "Credit Management"]
    [:div.mt-3
     [search-customer-form]]
    [search-results]]])

(defn customer-credits
  "Displays credit overview for a single customer"
  [route]
  (let [cust-id (:customer-id (r/path-params route))]
    (rf/dispatch [:customer/maybe-load cust-id])
    (rf/dispatch [:credits/load cust-id])
    (let [cust (rf/subscribe [:customer/info])]
      [l/default
       [:<>
        [:h3 (:name @cust) ": Credit Overview"]
        [:div.card
         [:div.card-body
          [:p "Credit overview goes here"]]]]])))
