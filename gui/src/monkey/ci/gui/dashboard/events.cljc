(ns monkey.ci.gui.dashboard.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.dashboard.db :as db]
            [monkey.ci.gui.loader :as lo]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load-recent-builds
 (lo/loader-evt-handler
  db/recent-builds
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-recent-builds
     {:org-id org-id
      :n 10}
     [::load-recent-builds--success]
     [::load-recent-builds--failure]])))

(rf/reg-event-db
 ::load-recent-builds--success
 (fn [db [_ resp]]
   (lo/on-success db db/recent-builds resp)))

(rf/reg-event-db
 ::load-recent-builds--failure
 (fn [db [_ err]]
   (lo/on-failure db db/recent-builds a/org-recent-builds-failed err)))
