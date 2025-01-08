(ns monkey.ci.gui.admin.credits.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
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
                   (t/add-sorting 1 :asc))
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

(defn- formatted-time [prop]
  (fn [obj]
    (when-let [t (prop obj)]
      (-> t
          (time/parse-epoch)
          (time/format-date)))))

(defn- issuances-table []
  [t/paged-table
   {:id ::credits
    :items-sub [:credits/issues]
    :loading {:sub [:credits/issues-loading?]}
    :columns (-> [{:label "Available from"
                   :value (formatted-time :from-time)
                   :sorter (t/prop-sorter :from-time)}
                  {:label "Amount"
                   :value :amount
                   :sorter (t/prop-sorter :amount)}
                  {:label "Type"
                   :value :type
                   :sorter (t/prop-sorter :type)}
                  {:label "Reason"
                   :value :reason}]
                 (t/add-sorting 0 :desc))}])

(defn- form-input [id lbl type & [desc]]
  [:div.mb-3
   [:label.form-label
    {:for id}
    lbl]
   [:input.form-control
    {:id id
     :name id
     :type type}]
   (when desc
     [:span.form-text desc])])

(defn- save-btn [sub]
  (let [saving? (rf/subscribe sub)]
    [:button.btn.btn-primary
     {:type :submit
      :disabled @saving?}
     [:span.me-2 [co/icon :save]] "Save"]))

(defn- issue-save-btn []
  (save-btn [:credits/issue-saving?]))

(defn- issue-credits-form []
  [:form
   {:on-submit (f/submit-handler [:credits/save-issue])}
   [form-input :amount "Credit amount" :number]
   [form-input :reason "Reason" :text "Optional informational message for the customer."]
   [form-input :from-time "Available from" :date "The date the credits become available for use."]
   [:div.d-flex.gap-2
    [issue-save-btn]
    [co/cancel-btn [:credits/cancel-issue]]]])

(defn- issue-credits []
  (let [show? (rf/subscribe [:credits/show-issue-form?])]
    (when @show?
      [:div.card
       [:div.card-body
        [:h5 "Issue Credits"]
        [:p "Create a new credit issuance for this customer.  One time only."]
        [issue-credits-form]]])))

(defn- issue-credits-btn []
  (let [shown? (rf/subscribe [:credits/show-issue-form?])]
    [co/icon-btn
     :plus-square
     "Issue Credits"
     [:credits/new-issue]
     (when @shown? {:disabled true})]))

(defn- issuances
  "Displays credit issuances tab contents"
  [cust-id]
  (rf/dispatch [:credits/load-issues cust-id])
  [:div.d-flex.flex-column.gap-3
   [:div.card
    [:div.card-body
     [:h5 "Credit Issuances"]
     [issuances-table]
     [co/alerts [:credits/issue-alerts]]
     [issue-credits-btn]]]
   [issue-credits]])

(defn- credit-sub-btn []
  (let [shown? (rf/subscribe [:credits/show-sub-form?])]
    [co/icon-btn
     :repeat
     "New Subscription"
     [:credits/new-sub]
     (when @shown? {:disabled true})]))

(defn- subs-table []
  [t/paged-table
   {:id ::credits
    :items-sub [:credits/subs]
    :loading {:sub [:credits/subs-loading?]}
    :columns (-> [{:label "Valid from"
                   :value (formatted-time :valid-from)
                   :sorter (t/prop-sorter :valid-from)}
                  {:label "Valid until"
                   :value (formatted-time :valid-until)
                   :sorter (t/prop-sorter :valid-until)}
                  {:label "Amount"
                   :value :amount
                   :sorter (t/prop-sorter :amount)}]
                 (t/add-sorting 0 :desc))}])

(defn- sub-save-btn []
  (save-btn [:credits/sub-saving?]))

(defn- sub-credits-form []
  [:form
   {:on-submit (f/submit-handler [:credits/save-sub])}
   [form-input :amount "Credit amount" :number]
   [form-input :valid-from "Valid from" :date "The date the subscription becomes active."]
   [form-input :valid-until "Valid until" :date "Optional date the subscription ends."]
   [:div.d-flex.gap-2
    [sub-save-btn]
    [co/cancel-btn [:credits/cancel-sub]]]])

(defn- sub-credits []
  (let [show? (rf/subscribe [:credits/show-sub-form?])]
    (when @show?
      [:div.card
       [:div.card-body
        [:h5 "Credit Subscription"]
        [:p "Create a new repeating credit issuance subscription for this customer."]
        [sub-credits-form]]])))

(defn- subscriptions
  "Displays credit subscriptions tab contents"
  [cust-id]
  (rf/dispatch [:credits/load-subs cust-id])
  [:div.d-flex.flex-column.gap-3
   [:div.card
    [:div.card-body
     [:h5 "Credit Subscriptions"]
     [subs-table]
     [co/alerts [:credits/sub-alerts]]
     [credit-sub-btn]]]
   [sub-credits]])

(defn- credit-tabs [cust-id]
  [tabs/tabs
   ::credit-overview
   [{:id ::issuances
     :header [co/tab-header :credit-card "Issuances"]
     :contents [issuances cust-id]}
    {:id ::subscriptions
     :header [co/tab-header :repeat "Subscriptions"]
     :contents [subscriptions cust-id]}]])

(defn customer-credits
  "Displays credit overview for a single customer"
  [route]
  (let [cust-id (:customer-id (r/path-params route))]
    (rf/dispatch [:customer/load cust-id])
    (fn [route]
      (let [cust (rf/subscribe [:customer/info])]
        [l/default
         [:<>
          [:h3.mb-3 (:name @cust) ": Credit Overview"]
          [credit-tabs cust-id]]]))))
