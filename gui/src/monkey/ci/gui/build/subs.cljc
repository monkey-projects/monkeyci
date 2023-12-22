(ns monkey.ci.gui.build.subs
  (:require [monkey.ci.gui.build.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 :build/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :build/logs
 (fn [db _]
   (db/logs db)))
