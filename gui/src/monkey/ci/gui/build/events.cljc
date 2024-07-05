(ns monkey.ci.gui.build.events
  (:require [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def stream-id ::event-stream)

(rf/reg-event-fx
 :build/init
 (fn [{:keys [db]} _]
   (when-not (db/initialized? db)
     {:dispatch-n [[:build/load]
                   [:customer/maybe-load (r/customer-id db)]
                   ;; Make sure we stop listening to events when we leave this page
                   [:route/on-page-leave [:build/leave]]
                   ;; TODO Only start reading events when the build has not finished yet
                   [:event-stream/start stream-id (r/customer-id db) [:build/handle-event]]]
      :db (db/set-initialized db true)})))

(rf/reg-event-fx
 :build/leave
 (fn [{:keys [db]} _]
   {:dispatch [:event-stream/stop stream-id]
    :db (db/unset-initialized db)}))

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

(defmulti handle-event (fn [_ evt] (:type evt)))

(defmethod handle-event :build/updated [db evt]
  (db/set-build db (:build evt)))

(defmethod handle-event :default [db evt]
  ;; Ignore
  db)

(rf/reg-event-db
 :build/handle-event
 (fn [db [_ evt]]
   (when (for-build? db evt)
     (handle-event db evt))))

(rf/reg-event-db
 :job/toggle
 (fn [db [_ job]]
   (db/toggle-expanded-job db (:id job))))
