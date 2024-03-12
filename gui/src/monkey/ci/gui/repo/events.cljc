(ns monkey.ci.gui.repo.events
  (:require [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :repo/load
 (fn [{:keys [db]} [_ cust-id]]
   (let [existing (cdb/customer db)]
     (cond-> {:db (db/set-builds db nil)}
       (not existing)
       (assoc :dispatch [:customer/load cust-id])))))

(rf/reg-event-fx
 :builds/load
 (fn [{:keys [db]} _]
   (let [params (get-in db [:route/current :parameters :path])]
     {:db (-> db
              (db/set-alerts [{:type :info
                               :message "Loading builds for repository..."}])
              (db/set-builds nil))
      :dispatch [:secure-request
                 :get-builds
                 (select-keys params [:customer-id :repo-id])
                 [:builds/load--success]
                 [:builds/load--failed]]})))

(rf/reg-event-db
 :builds/load--success
 (fn [db [_ {builds :body}]]
   (-> db
       (db/set-builds builds)
       (db/reset-alerts))))

(rf/reg-event-db
 :builds/load--failed
 (fn [db [_ err op]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not load builds: " (u/error-msg err))}])))

(defmulti handle-event (fn [_ evt] (:type evt)))

(defn- update-build [db build]
  (db/update-build db build))

(defmethod handle-event :build/start [db evt]
  (update-build db (:build evt)))

(defmethod handle-event :build/completed [db evt]
  (update-build db (:build evt)))

(defmethod handle-event :default [db evt]
  ;; Ignore
  db)

(defn- for-repo? [db evt]
  (let [get-id (juxt :customer-id :repo-id)]
    (= (get-id (:build evt))
       (-> (r/current db)
           (r/path-params)
           (get-id)))))

(rf/reg-event-db
 :repo/handle-event
 (fn [db [_ evt]]
   (when (for-repo? db evt)
     (handle-event db evt))))
