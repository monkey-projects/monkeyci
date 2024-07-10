(ns monkey.ci.gui.params.subs
  (:require [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/params db/edit-params)
(u/db-sub :params/alerts db/alerts)
(u/db-sub :params/loading? (comp true? db/loading?))
(u/db-sub :params/saving? (comp true? db/saving?))

(rf/reg-sub
 :customer/param
 :<- [:customer/params]
 (fn [params [_ set-idx param-idx]]
   (some-> params
           (nth set-idx)
           :parameters
           (nth param-idx))))
