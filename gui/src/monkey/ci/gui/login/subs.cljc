(ns monkey.ci.gui.login.subs
  (:require [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :login/submitting? db/submitting?)
(u/db-sub :login/alerts db/alerts)
(u/db-sub :login/token db/token)
(u/db-sub :login/github-client-id (comp :client-id db/github-config))
(u/db-sub :login/bitbucket-client-id (comp :client-id db/bitbucket-config))
(u/db-sub :login/codeberg-client-id (comp :client-id db/codeberg-config))

(defn- add-github-user [u db]
  (let [gu (db/github-user db)]
    (cond-> u
      gu (-> (assoc :github gu)
             (merge (select-keys gu [:name :avatar-url]))))))

(defn- add-bitbucket-user [u db]
  (let [bu (db/bitbucket-user db)]
    (cond-> u
      bu (assoc :bitbucket bu
                :name (:display-name bu)
                :avatar-url (get-in bu [:links :avatar :href])))))

(rf/reg-sub
 :login/user
 (fn [db _]
   (-> (db/user db)
       (add-github-user db)
       (add-bitbucket-user db))))

(rf/reg-sub
 :login/github-user?
 :<- [:login/user]
 (fn [u _]
   (some? (:github u))))

(rf/reg-sub
 :login/bitbucket-user?
 :<- [:login/user]
 (fn [u _]
   (some? (:bitbucket u))))
