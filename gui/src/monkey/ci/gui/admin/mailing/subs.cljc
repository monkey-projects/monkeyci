(ns monkey.ci.gui.admin.mailing.subs
  (:require [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub ::mailing-list (comp reverse
                               (partial sort-by :creation-time)
                               db/get-mailings))
(u/db-sub ::loading? db/loading?)
(u/db-sub ::alerts db/get-alerts)

(u/db-sub ::editing db/get-editing)
(u/db-sub ::edit-alerts db/get-editing-alerts)

(u/db-sub ::sent-mailings (comp reverse
                                (partial sort-by :sent-at)
                                db/get-sent-mailings))
(u/db-sub ::sent-alerts db/get-sent-alerts)
(u/db-sub ::sent-loading? db/sent-loading?)

(u/db-sub ::new-delivery db/get-new-delivery)

(rf/reg-sub
 ::show-delivery?
 :<- [::new-delivery]
 (fn [d _]
   (some? d)))
