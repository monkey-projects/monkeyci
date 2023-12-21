(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.login.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-db
 :login/submit
 (fn [db [_ fd]]
   (db/set-submitting db)))

(rf/reg-event-db
 :login/authenticated
 (fn [db [_ user]]
   (-> db
       (db/set-user user)
       (db/unset-submitting))))
