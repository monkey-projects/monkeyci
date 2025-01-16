(ns monkey.ci.gui.admin.search
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def cust-by-name ::cust-by-name)
(def cust-by-id ::cust-by-id)

(defn get-customers-by-name [db]
  (lo/get-value db cust-by-name))

(defn get-customers-by-id [db]
  (lo/get-value db cust-by-id))

(defn get-customers [db]
  (concat (get-customers-by-name db)
          (get-customers-by-id db)))

(defn reset-customers [db]
  (-> db
      (lo/set-value cust-by-name [])
      (lo/set-value cust-by-id [])))

(defn customers-loading? [db]
  (or (lo/loading? db cust-by-name)
      (lo/loading? db cust-by-id)))

(defn customers-loaded? [db]
  (or (lo/loaded? db cust-by-name)
      (lo/loaded? db cust-by-id)))

(rf/reg-event-fx
 :admin/customer-search
 (fn [{:keys [db]} [_ details]]
   (let [f (-> details (get :customer-filter) first)]
     ;; Search by name and id
     {:dispatch-n [[:admin/customer-search-by-name details]
                   [:admin/customer-search-by-id details]]
      :db (reset-customers db)})))

(def cust-filter (comp first :customer-filter))

(def id->key
  {cust-by-id :id
   cust-by-name :name})

(defn- search-customer-req [id _ [_ details]]
  [:secure-request
   :search-customers
   {(id->key id) (cust-filter details)}
   [:admin/customer-search--success id]
   [:admin/customer-search--failed id]])

(rf/reg-event-fx
 :admin/customer-search-by-name
 (lo/loader-evt-handler
  cust-by-name
  search-customer-req))

(rf/reg-event-fx
 :admin/customer-search-by-id
 (lo/loader-evt-handler
  cust-by-id
  search-customer-req))

(rf/reg-event-db
 :admin/customer-search--success
 (fn [db [_ id resp]]
   (lo/on-success db id resp)))

(rf/reg-event-db
 :admin/customer-search--failed
 (fn [db [_ id resp]]
   (lo/on-failure db id a/cust-search-failed resp)))

(u/db-sub :admin/customers-loading? customers-loading?)
(u/db-sub :admin/customers-loaded? customers-loaded?)
(u/db-sub :admin/customers get-customers)

(defn cust-search-btn []
  (let [l (rf/subscribe [:admin/customers-loading?])]
    [:button.btn.btn-primary.btn-icon
     {:type :submit
      :on-click (f/submit-handler [:admin/customer-search] :customer-search)
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

(defn- customers-table [get-route]
  (letfn [(render-id [{:keys [id] :as obj}]
            [:a {:href (apply r/path-for (get-route obj))} id])]
    [t/paged-table
     {:id ::customers
      :items-sub [:admin/customers]
      :columns (-> [{:label "Id"
                     :value render-id
                     :sorter (t/prop-sorter :id)}
                    {:label "Name"
                     :value :name
                     :sorter (t/prop-sorter :name)}]
                   (t/add-sorting 1 :asc))
      :loading {:sub [:admin/customers-loading?]}
      :on-row-click #(rf/dispatch (into [:route/goto] (get-route %)))}]))

(defn search-results [opts]
  (let [loaded? (rf/subscribe [:admin/customers-loaded?])]
    [:div.card
     [:div.card-body
      (if @loaded?
        [customers-table (:get-route opts)]
        (or
         (:init-view opts)
         [:p.card-text "Search for a customer."]))]]))

(defn search-customers [opts]
  [:<>
   [search-customer-form]
   [search-results opts]])
