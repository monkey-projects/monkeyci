(ns monkey.ci.gui.admin.mailing.subs
  (:require [monkey.ci.gui.admin.mailing.db :as db]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::mailing-list
 (fn [db _]
   (db/get-mailings db)))
