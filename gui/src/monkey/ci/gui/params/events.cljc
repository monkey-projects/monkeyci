(ns monkey.ci.gui.params.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.routing :as r]
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
       ;; Set both original and edit params.  This will be used when
       ;; user wants to save a part of the params, or cancel editing.
       (db/set-params params)
       (db/set-edit-params params)
       (db/unmark-loading))))

(rf/reg-event-db
 :params/load--failed
 (fn [db [_ err]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Failed to load customer params: " (u/error-msg err))}])
       (db/unmark-loading))))

(rf/reg-event-db
 :params/new-set
 (fn [db _]
   (db/update-edit-params db conj {})))

(rf/reg-event-db
 :params/cancel-set
 (fn [db [_ idx]]
   ;; Replace with original, or remove if there is no original
   (if-let [orig (get (db/params db) idx)]
     (db/set-edit-params db (vec (mc/replace-nth idx orig (db/edit-params db))))
     (db/update-edit-params db (comp vec (partial mc/remove-nth idx))))))

(rf/reg-event-db
 :params/delete-set
 (fn [db [_ idx]]
   (db/update-edit-params db (comp vec (partial mc/remove-nth idx)))))

(rf/reg-event-db
 :params/new-param
 (fn [db [_ idx]]
   (let [orig (get (db/edit-params db) idx)]
     (db/set-edit-params db (vec (mc/replace-nth idx (update orig :parameters conj {})
                                                 (db/edit-params db)))))))

(rf/reg-event-db
 :params/delete-param
 (fn [db [_ set-idx param-idx]]
   (db/update-edit-param-set db set-idx update :parameters (comp vec (partial mc/remove-nth param-idx)))))

(rf/reg-event-fx
 :params/save-all
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :update-customer-params
               {:customer-id (r/customer-id db)
                :params (db/edit-params db)}
               [:params/save-all--success]
               [:params/save-all--failed]]
    :db (-> db
            (db/mark-saving)
            (db/clear-alerts))}))

(rf/reg-event-db
 :params/save-all--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/unmark-saving)
       (db/set-params body)
       (db/set-edit-params body))))

(rf/reg-event-db
 :params/save-all--failed
 (fn [db [_ err]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Failed to save customer params: " (u/error-msg err))}])
       (db/unmark-saving))))

(rf/reg-event-db
 :params/cancel-all
 (fn [db _]
   (db/set-edit-params db (db/params db))))

(rf/reg-event-db
 :params/label-changed
 (fn [db [_ set-idx param-idx new-val]]
   (db/update-edit-param db set-idx param-idx assoc :name new-val)))

(rf/reg-event-db
 :params/value-changed
 (fn [db [_ set-idx param-idx new-val]]
   (db/update-edit-param db set-idx param-idx assoc :value new-val)))
