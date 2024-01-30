(ns monkey.ci.gui.build.events
  (:require [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn load-logs-req [db]
  [:secure-request
   :get-build-logs
   (r/path-params (:route/current db))
   [:build/load-logs--success]
   [:build/load-logs--failed]])

(defn load-build-req [db]
  [:secure-request
   :get-build
   (r/path-params (:route/current db))
   [:build/load--success]
   [:build/load--failed]])

(rf/reg-event-fx
 :build/load-logs
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-alerts [{:type :info
                             :message "Loading logs for build..."}])
            (db/set-logs nil))
    :dispatch (load-logs-req db)}))

(rf/reg-event-db
 :build/load-logs--success
 (fn [db [_ {logs :body}]]
   (-> db
       (db/set-logs logs)
       (db/reset-alerts)
       (db/clear-logs-reloading))))

(rf/reg-event-db
 :build/load-logs--failed
 (fn [db [_ err op]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Could not load build logs: " (u/error-msg err))}])
       (db/clear-logs-reloading))))

(rf/reg-event-fx
 :build/load
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-alerts [{:type :info
                             :message "Loading build details..."}])
            (db/set-build nil))
    :dispatch (load-build-req db)}))

(rf/reg-event-db
 :build/load--success
 (fn [db [_ {build :body}]]
   (-> db
       (db/set-build build)
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
   {:dispatch-n [(load-build-req db)
                 (load-logs-req db)]
    :db (-> db
            (db/set-reloading)
            (db/set-last-reload-time (:time/now cofx)))}))

(rf/reg-event-fx
 :build/download-log
 (fn [{:keys [db]} [_ path]]
   {:db (-> db
            (db/set-current-log nil)
            (db/mark-downloading)
            (db/reset-log-alerts)
            (db/set-log-path path))
    :dispatch [:secure-request
               :download-log
               (-> (r/path-params (:route/current db))
                   (assoc :path path))
               [:build/download-log--success]
               [:build/download-log--failed]]}))

(rf/reg-event-db
 :build/download-log--success
 (fn [db [_ {log-contents :body}]]
   (-> db
       (db/reset-downloading)
       (db/set-current-log log-contents))))

(rf/reg-event-db
 :build/download-log--failed
 (fn [db [_ {err :body}]]
   (-> db
       (db/reset-downloading)
       (db/set-log-alerts [{:type :danger
                            :message (u/error-msg err)}]))))

(rf/reg-event-db
 :build/auto-reload-changed
 (fn [db [_ v]]
   (db/set-auto-reload db v)))
