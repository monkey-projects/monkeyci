(ns monkey.ci.gui.dashboard.main.events
  (:require [monkey.ci.gui.dashboard.main.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::initialize-db
 (fn [db _]
   (-> db
       (db/set-assets-url "http://localhost:8083/assets/img/")
       (db/set-metrics :total-runs {:curr-value 128
                                    :last-value 100
                                    :avg-value 150
                                    :status :success}))))

(rf/reg-event-fx
 ::initialize
 (fn [{:keys [db]} _]
   (lo/on-initialize db ::dashboard
                     {:init-events [[:org/load (r/org-id db)]]
                      :event-handler-event [::handle-event]})))

(rf/reg-event-fx
 ::recent-builds
 (lo/loader-evt-handler
  db/recent-builds
  (fn [_ {:keys [db]} _]
    [:secure-request
     :get-recent-builds
     {:org-id (r/org-id db) :n 10}
     [::recent-builds--success]
     [::recent-builds--failure]])))

(rf/reg-event-db
 ::recent-builds--success
 (fn [db [_ resp]]
   (lo/on-success db db/recent-builds resp)))

(rf/reg-event-db
 ::recent-builds--failure
 (fn [db [_ err]]
   (lo/on-failure db db/recent-builds "Failed to load recent builds" err)))

(rf/reg-event-db
 ::handle-event
 (fn [db [_ evt]]
   ;; TODO
   (println "Dashboard event:" evt)))
