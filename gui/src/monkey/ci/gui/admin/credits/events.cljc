(ns monkey.ci.gui.admin.credits.events
  (:require [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :credits/customer-search
 (fn [{:keys [db]} [_ details]]
   (let [f (-> details (get :customer-filter) first)]
     ;; Search by name and id
     {:dispatch-n [[:credits/customer-search-by-name details]
                   [:credits/customer-search-by-id details]]
      :db (db/reset-customers db)})))

(def cust-filter (comp first :customer-filter))

(def id->key
  {db/cust-by-id :id
   db/cust-by-name :name})

(defn- search-customer-req [id _ [_ details]]
  [:secure-request
   :search-customers
   {(id->key id) (cust-filter details)}
   [:credits/customer-search--success id]
   [:credits/customer-search--failed id]])

(rf/reg-event-fx
 :credits/customer-search-by-name
 (lo/loader-evt-handler
  db/cust-by-name
  search-customer-req))

(rf/reg-event-fx
 :credits/customer-search-by-id
 (lo/loader-evt-handler
  db/cust-by-id
  search-customer-req))

(rf/reg-event-db
 :credits/customer-search--success
 (fn [db [_ id resp]]
   (lo/on-success db id resp)))

(rf/reg-event-db
 :credits/customer-search--failed
 (fn [db [_ id resp]]
   (lo/on-failure db id a/cust-search-failed resp)))

(rf/reg-event-fx
 :credits/load
 (fn [{:keys [db]} [_ cust-id]]
   ;; Load credit details for customer
   ))
