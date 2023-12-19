(ns monkey.ci.gui.customer.subs
  (:require [monkey.ci.gui.customer.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 :customer/info
 (fn [db _]
   (db/customer db)))

(rf/reg-sub
 :customer/alerts
 (fn [db _]
   (db/alerts db)))
