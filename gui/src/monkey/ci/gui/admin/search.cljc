(ns monkey.ci.gui.admin.search
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def org-by-name ::org-by-name)
(def org-by-id ::org-by-id)

(defn get-orgs-by-name [db]
  (lo/get-value db org-by-name))

(defn get-orgs-by-id [db]
  (lo/get-value db org-by-id))

(defn get-orgs [db]
  (concat (get-orgs-by-name db)
          (get-orgs-by-id db)))

(defn reset-orgs [db]
  (-> db
      (lo/set-value org-by-name [])
      (lo/set-value org-by-id [])))

(defn orgs-loading? [db]
  (or (lo/loading? db org-by-name)
      (lo/loading? db org-by-id)))

(defn orgs-loaded? [db]
  (or (lo/loaded? db org-by-name)
      (lo/loaded? db org-by-id)))

(rf/reg-event-fx
 :admin/org-search
 (fn [{:keys [db]} [_ details]]
   (let [f (-> details (get :org-filter) first)]
     ;; Search by name and id
     {:dispatch-n [[:admin/org-search-by-name details]
                   [:admin/org-search-by-id details]]
      :db (reset-orgs db)})))

(def org-filter (comp first :org-filter))

(def id->key
  {org-by-id :id
   org-by-name :name})

(defn- search-org-req [id _ [_ details]]
  [:secure-request
   :search-orgs
   {(id->key id) (org-filter details)}
   [:admin/org-search--success id]
   [:admin/org-search--failed id]])

(rf/reg-event-fx
 :admin/org-search-by-name
 (lo/loader-evt-handler
  org-by-name
  search-org-req))

(rf/reg-event-fx
 :admin/org-search-by-id
 (lo/loader-evt-handler
  org-by-id
  search-org-req))

(rf/reg-event-db
 :admin/org-search--success
 (fn [db [_ id resp]]
   (lo/on-success db id resp)))

(rf/reg-event-db
 :admin/org-search--failed
 (fn [db [_ id resp]]
   (lo/on-failure db id a/org-search-failed resp)))

(u/db-sub :admin/orgs-loading? orgs-loading?)
(u/db-sub :admin/orgs-loaded? orgs-loaded?)
(u/db-sub :admin/orgs get-orgs)

(defn org-search-btn []
  (let [l (rf/subscribe [:admin/orgs-loading?])]
    [:button.btn.btn-primary.btn-icon
     {:type :submit
      :on-click (f/submit-handler [:admin/org-search] :org-search)
      :disabled @l}
     [:i.bi-search]]))

(defn search-org-form []
  [:div.bg-primary-dark.overflow-hidden
   [:div.container.position-relative.content-space-1
    ;; Search form, does nothing for now
    [:div.w-lg-75.mx-lg-auto
     [:form#org-search
      [:div.input-card
       [:div.input-card-form
        [:label.form-label.visually-hidden {:for :org-filter}
         "Search for org"]
        [:input.form-control {:type :text
                              :name :org-filter
                              :id :org-filter
                              :placeholder "Search for org"
                              :aria-label "Search for org"}]]
       [org-search-btn]]]]]])

(defn- orgs-table [get-route]
  (letfn [(render-id [{:keys [id] :as obj}]
            [:a {:href (apply r/path-for (get-route obj))} id])]
    [t/paged-table
     {:id ::orgs
      :items-sub [:admin/orgs]
      :columns (-> [{:label "Id"
                     :value render-id
                     :sorter (t/prop-sorter :id)}
                    {:label "Name"
                     :value :name
                     :sorter (t/prop-sorter :name)}]
                   (t/add-sorting 1 :asc))
      :loading {:sub [:admin/orgs-loading?]}
      :on-row-click #(rf/dispatch (into [:route/goto] (get-route %)))}]))

(defn search-results [opts]
  (let [loaded? (rf/subscribe [:admin/orgs-loaded?])]
    [:div.card
     [:div.card-body
      (if @loaded?
        [orgs-table (:get-route opts)]
        (or
         (:init-view opts)
         [:p.card-text "Search for a org."]))]]))

(defn search-orgs [opts]
  [:<>
   [search-org-form]
   [search-results opts]])
