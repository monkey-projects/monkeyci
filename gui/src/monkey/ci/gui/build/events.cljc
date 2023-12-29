(ns monkey.ci.gui.build.events
  (:require [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :build/load-logs
 (fn [{:keys [db]} _]
   (let [p (r/path-params (:route/current db))]
     {:db (-> db
              (db/set-alerts [{:type :info
                               :message "Loading logs for build..."}])
              (db/set-logs nil))
      :dispatch [:martian.re-frame/request
                 :get-build-logs
                 p
                 [:build/load-logs--success]
                 [:build/load-logs--failed]]})))

(rf/reg-event-db
 :build/load-logs--success
 (fn [db [_ {logs :body}]]
   (-> db
       (db/set-logs logs)
       (db/reset-alerts))))

(rf/reg-event-db
 :build/load-logs--failed
 (fn [db [_ err op]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not load build logs: " (or (:message err) (str err)))}])))
