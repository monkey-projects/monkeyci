(ns monkey.ci.gui.login.subs
  (:require [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :login/submitting? db/submitting?)
(u/db-sub :login/alerts db/alerts)
(u/db-sub :login/token db/token)
(u/db-sub :login/github-client-id (comp :client-id db/github-config))
(u/db-sub :login/bitbucket-client-id (comp :client-id db/bitbucket-config))

(rf/reg-sub
 :login/user
 (fn [db _]
   (let [gu (db/github-user db)]
     (some-> (db/user db)
             (assoc :github gu)
             (merge (select-keys gu [:name :avatar-url]))))))

