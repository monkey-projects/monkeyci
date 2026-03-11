(ns monkey.ci.gui.notifications.events
  (:require [clojure.tools.reader.edn :as edn]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.notifications.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::confirm-email
 (fn [{:keys [db]} [_ {:keys [code]}]]
   {:dispatch [:martian.re-frame/request
               :confirm-email
               ;; The code is actually a base64 encoded edn string
               (some-> code
                       (u/b64->)
                       (edn/read-string))
               [::confirm-email--success]
               [::confirm-email--failure]]
    :db (-> db
            (db/set-confirming)
            (db/set-alerts []))}))

(rf/reg-event-db
 ::confirm-email--success
 (fn [db _]
   (db/reset-confirming db)))

(rf/reg-event-db
 ::confirm-email--failure
 (fn [db [_ err]]
   (-> db
       (db/reset-confirming)
       (db/set-alerts [(a/confirm-email-failed err)]))))

(rf/reg-event-fx
 ::unregister-email
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:martian.re-frame/request
               :unregister-email
               params
               [::unregister-email--success]
               [::unregister-email--failure]]
    :db (-> db
            (db/set-unregistering)
            (db/set-alerts []))}))

(rf/reg-event-db
 ::unregister-email--success
 (fn [db _]
   (db/reset-unregistering db)))

(rf/reg-event-db
 ::unregister-email--failure
 (fn [db [_ err]]
   (-> db
       (db/reset-unregistering)
       (db/set-alerts [(a/unregister-email-failed err)]))))
