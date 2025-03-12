(ns monkey.ci.gui.admin.clean.events
  (:require [monkey.ci.gui.admin.clean.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::clean
 (lo/loader-evt-handler
  db/clean
  (fn [& _]
    [:secure-request
     :admin-reaper
     {}
     [::clean--success]
     [::clean--failed]])))

(rf/reg-event-db
 ::clean--success
 (fn [db [_ resp]]
   (lo/on-success db db/clean resp)))

(rf/reg-event-db
 ::clean--failed
 (fn [db [_ resp]]
   (lo/on-failure db db/clean a/clean-proc-failed resp)))
