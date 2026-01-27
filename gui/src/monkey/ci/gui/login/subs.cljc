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

(defn- add-codeberg-user [u db]
  (let [cu (db/codeberg-user db)]
    (cond-> u
      cu (assoc :codeberg cu
                :name (:login cu)
                :avatar-url (:avatar-url cu)))))

(rf/reg-sub
 :login/user
 (fn [db _]
   (-> (db/user db)
       (add-github-user db)
       (add-bitbucket-user db)
       (add-codeberg-user db))))

(rf/reg-sub
 ::user-of-type?
 :<- [:login/user]
 (fn [u [_ t]]
   (some? (t u))))

(rf/reg-sub
 :login/github-user?
 :<- [::user-of-type? :github]
 (fn [u _]
   u))

(rf/reg-sub
 :login/bitbucket-user?
 :<- [::user-of-type? :bitbucket]
 (fn [u _]
   u))

(rf/reg-sub
 :login/codeberg-user?
 :<- [::user-of-type? :codeberg]
 (fn [u _]
   u))
