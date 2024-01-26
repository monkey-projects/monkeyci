(ns monkey.ci.gui.login.subs
  (:require [monkey.ci.gui.login.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 :login/submitting?
 (fn [db _]
   (db/submitting? db)))

(rf/reg-sub
 :login/user
 (fn [db _]
   (db/user db)))

(rf/reg-sub
 :login/alerts
 (fn [db _]
   (db/alerts db)))

(rf/reg-sub
 :login/token
 (fn [db _]
   (db/token db)))

(rf/reg-sub
 :login/token
 (fn [db _]
   (db/token db)))

(rf/reg-sub
 :login/github-client-id
 (fn [db _]
   (:client-id (db/github-config db))))
