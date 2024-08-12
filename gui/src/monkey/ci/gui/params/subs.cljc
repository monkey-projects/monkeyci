(ns monkey.ci.gui.params.subs
  (:require [medley.core :as mc]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/params db/params)
(u/db-sub :params/alerts db/alerts)
(u/db-sub :params/loading? (comp true? db/loading?))
(u/db-sub :params/saving? (comp true? db/saving?))
(u/db-sub :params/set-deleting? (comp true? db/set-deleting?))
(u/db-sub :params/set-alerts db/get-set-alerts)
(u/db-sub :params/editing? db/editing?)
(u/db-sub :params/editing db/get-editing)

(rf/reg-sub
 :customer/param
 (fn [db [_ set-id param-idx]]
   (some-> (db/get-editing db set-id)
           :parameters
           (nth param-idx))))

(rf/reg-sub
 :params/new-sets
 (fn [db _]
   (->> (db/edit-sets db)
        (mc/filter-keys db/temp-id?)
        (vals))))
