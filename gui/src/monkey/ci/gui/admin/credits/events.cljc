(ns monkey.ci.gui.admin.credits.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.time :as t]
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
 (lo/loader-evt-handler
  db/credits
  (fn [_ _ [_ cust-id]]
    [:secure-request
     :get-customer-credit-overview
     {:customer-id cust-id}
     [:credits/load--success]
     [:credits/load--failed]])))

(rf/reg-event-db
 :credits/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/credits resp)))

(rf/reg-event-db
 :credits/load--failed
 (fn [db [_ resp]]
   (lo/on-failure db db/credits a/credit-overview-failed resp)))

(rf/reg-event-fx
 :credits/save
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:secure-request
               :issue-credits
               {:credits
                (-> (select-keys params [:reason :amount :from-time])
                    (as-> t (mc/map-vals first t))
                    (mc/update-existing :from-time (comp t/to-epoch t/parse-iso)))
                :customer-id (r/customer-id db)}
               [:credits/save--success]
               [:credits/save--failed]]
    :db (-> db
            (db/set-saving)
            (db/reset-credit-alerts))}))

(rf/reg-event-db
 :credits/save--success
 (fn [db [_ {resp :body}]]
   (-> db
       (db/reset-saving)
       (db/update-credits conj resp))))

(rf/reg-event-db
 :credits/save--failed
 (fn [db [_ resp]]
   (-> db
       (db/set-credit-alerts [(a/credit-save-failed resp)])
       (db/reset-saving))))

(rf/reg-event-db
 :credits/cancel
 (fn [db _]
   ;; TODO Reset form
   ))