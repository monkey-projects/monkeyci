(ns monkey.ci.gui.build.events
  (:require [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :build/init
 (fn [{:keys [db]} _]
   (lo/on-initialize
    db db/id
    {:init-events         [[:build/load]
                           [:customer/maybe-load (r/customer-id db)]]
     :leave-event         [:build/leave]
     :event-handler-event [:build/handle-event]})))

(rf/reg-event-fx
 :build/leave
 (fn [{:keys [db]} _]
   (lo/on-leave db db/id)))

(defn load-build-req [db]
  [:secure-request
   :get-build
   (r/path-params (:route/current db))
   [:build/load--success]
   [:build/load--failed]])

(rf/reg-event-fx
 :build/load
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-alerts [{:type :info
                             :message "Loading build details..."}])
            (db/set-build nil))
    :dispatch (load-build-req db)}))

(rf/reg-event-fx
 :build/maybe-load
 (fn [{:keys [db]} _]
   (let [existing (db/build db)
         id (-> (r/current db) r/path-params :build-id)]
     (when-not (= (:id existing) id)
       {:dispatch [:build/load id]}))))

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
 (fn [db [_ {build :body}]]
   (-> db
       (db/set-build (convert-build build))
       (db/reset-alerts)
       (db/clear-build-reloading))))

(rf/reg-event-db
 :build/load--failed
 (fn [db [_ err op]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Could not load build details: " (u/error-msg err))}])
       (db/clear-build-reloading))))

(rf/reg-event-fx
 :build/reload
 [(rf/inject-cofx :time/now)]
 (fn [{:keys [db] :as cofx} _]
   {:dispatch (load-build-req db)
    :db (db/set-reloading db)}))

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

