(ns monkey.ci.gui.build.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :build/init
 (fn [{:keys [db]} _]
   (lo/on-initialize
    db (db/get-id db)
    {:init-events         [[:build/load]
                           [:customer/maybe-load (r/customer-id db)]]
     :leave-event         [:build/leave]
     :event-handler-event [:build/handle-event]})))

(rf/reg-event-fx
 :build/leave
 (fn [{:keys [db]} _]
   (lo/on-leave db (db/get-id db))))

(defn load-build-req [db]
  [:secure-request
   :get-build
   (r/path-params (:route/current db))
   [:build/load--success]
   [:build/load--failed]])

(defn- same-build?
  "True if the build in db is the same as referred to by the current route"
  [db]
  (let [id (juxt :customer-id :repo-id :build-id)]
    (= (id (r/path-params (r/current db)))
       (id (db/get-build db)))))

(rf/reg-event-fx
 :build/load
 (fn [{:keys [db]} _]
   {:db (cond-> (db/reset-alerts db)
          (not (same-build? db)) (db/set-build nil))
    :dispatch (load-build-req db)}))

(defn- convert-build
  "Builds received from requests are slightly different from those received as events.
   The jobs are in a vector instead of a map.  This function converts the received build
   in event format."
  [build]
  (letfn [(to-map [jobs]
            (reduce (fn [r j]
                      (assoc r (:id j) j))
                    {}
                    jobs))]
    (update-in build [:script :jobs] to-map)))

(rf/reg-event-db
 :build/load--success
 (fn [db [_ {build :body :as resp}]]
   (-> db
       (lo/on-success (db/get-id db) resp)
       ;; Override build with conversion
       (db/set-build (-> (convert-build build)
                         ;; Also add customer and repo id because they don't come in the reply
                         (merge (select-keys (r/path-params (r/current db))
                                             [:customer-id :repo-id])))))))

(rf/reg-event-db
 :build/load--failed
 (fn [db [_ err op]]
   (lo/on-failure db (db/get-id db) "Could not load build details: " err)))

(rf/reg-event-fx
 :build/reload
 (fn [{:keys [db] :as cofx} _]
   {:dispatch (load-build-req db)
    :db (lo/set-loading db (db/get-id db))}))

(defn- for-build? [db evt]
  (let [get-id (juxt :customer-id :repo-id :build-id)]
    (= (:sid evt)
       (-> (r/current db)
           (r/path-params)
           (get-id)))))

(defn handle-event [db evt]
  (cond-> db
    (= :build/updated (:type evt)) (db/set-build (:build evt))))

(rf/reg-event-db
 :build/handle-event
 (fn [db [_ evt]]
   (when (for-build? db evt)
     (handle-event db evt))))

(rf/reg-event-fx
 :build/cancel
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :cancel-build
               (r/path-params (:route/current db))
               [:build/cancel--success]
               [:build/cancel--failed]]
    :db (db/mark-canceling db)}))

(rf/reg-event-db
 :build/cancel--success
 (fn [db _]
   (-> db
       (db/reset-canceling)
       (db/set-alerts
        [{:type :warning
          :message "The build is being canceled.  Some jobs may continue to run until they too are canceled."}]))))

(rf/reg-event-db
 :build/cancel--failed
 (fn [db [_ err]]
   (-> db
       (db/reset-canceling)
       (db/set-alerts
        [{:type :danger
          :message (str "Unable to cancel this build: " (u/error-msg err))}]))))

(rf/reg-event-fx
 :build/retry
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :retry-build
               (r/path-params (:route/current db))
               [:build/retry--success]
               [:build/retry--failed]]
    :db (db/mark-retrying db)}))

(rf/reg-event-db
 :build/retry--success
 (fn [db [_ resp]]
   (-> db
       (db/reset-retrying)
       (db/set-alerts
        [(a/build-retry-success (:body resp))]))))

(rf/reg-event-db
 :build/retry--failed
 (fn [db [_ err]]
   (-> db
       (db/reset-retrying)
       (db/set-alerts
        [(a/build-retry-failed err)]))))
