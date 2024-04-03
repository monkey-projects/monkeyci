(ns monkey.ci.gui.repo.events
  (:require [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def stream-id ::event-stream)

(rf/reg-event-fx
 :repo/init
 (fn [{:keys [db]} _]
   (when-not (db/initialized? db)
     (let [cust-id (r/customer-id db)]
       {:dispatch-n [[:repo/load cust-id]
                     ;; Make sure we stop listening to events when we leave this page
                     [:route/on-page-leave [:repo/leave]]
                     ;; TODO Only do this if we're not listening already (e.g. code change reload)
                     [:event-stream/start stream-id cust-id [:repo/handle-event]]]
        :db (-> db
                (db/set-initialized true)
                (db/set-builds nil))}))))

(rf/reg-event-fx
 :repo/leave
 (fn [{:keys [db]} _]
   {:dispatch [:event-stream/stop stream-id]
    :db (db/unset-initialized db)}))

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

(defmethod handle-event :build/end [db evt]
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

(rf/reg-event-db
 :repo/show-trigger-build
 (fn [db _]
   (db/set-show-trigger-form db true)))

(rf/reg-event-db
 :repo/hide-trigger-build
 (fn [db _]
   (db/set-show-trigger-form db nil)))

(rf/reg-event-fx
 :repo/trigger-build
 (fn [{:keys [db]} [_ form-vals]]
   (log/debug "Triggering build with form values:" (str form-vals))
   (let [params (get-in db [:route/current :parameters :path])]
     {:db (-> db
              (db/set-triggering)
              (db/reset-alerts))
      :dispatch [:secure-request
                 :trigger-build
                 (select-keys params [:customer-id :repo-id])
                 [:repo/trigger-build--success]
                 [:repo/trigger-build--failed]]})))

(rf/reg-event-db
 :repo/trigger-build--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-alerts [{:type :info
                        :message (str "Build " (:build-id body) " started.")}])
       (db/set-show-trigger-form nil))))

(rf/reg-event-db
 :repo/trigger-build--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not start build: " (u/error-msg err))}])))
