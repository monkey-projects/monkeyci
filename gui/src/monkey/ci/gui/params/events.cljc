(ns monkey.ci.gui.params.events
  (:require [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :params/load
 (fn [{:keys [db]} [_ cust-id]]
   {:db (-> db
            (db/mark-loading)
            (db/clear-alerts))
    :dispatch [:secure-request
               :get-customer-params
               {:customer-id cust-id}
               [:params/load--success]
               [:params/load--failed]]}))

(rf/reg-event-db
 :params/load--success
 (fn [db [_ {params :body}]]
   (-> db
       (db/set-params params)
       (db/unmark-loading))))

(rf/reg-event-db
 :params/load--failed
 (fn [db [_ err]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Failed to load customer params: " (u/error-msg err))}])
       (db/unmark-loading))))
