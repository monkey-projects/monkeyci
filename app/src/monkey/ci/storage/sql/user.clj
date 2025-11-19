(ns monkey.ci.storage.sql.user
  (:require [clojure.set :as cset]
            [medley.core :as mc]
            [monkey.ci.entities
             [core :as ec]
             [org :as eo]
             [user :as eu]]
            [monkey.ci.storage.sql
             [common :as sc]
             [org :as so]]))

(defn- user->db [user]
  (-> user
      (sc/id->cuid)
      (select-keys [:cuid :type :type-id :email])
      (mc/update-existing :type name)
      (mc/update-existing :type-id str)))

(defn- db->user [user]
  (-> user
      (sc/cuid->id)
      (select-keys [:id :type :type-id :email])
      (mc/update-existing :type keyword)))

(defn- insert-user [conn user]
  (let [{:keys [id] :as ins} (ec/insert-user conn (user->db user))
        ids (eo/org-ids-by-cuids conn (:orgs user))]
    (ec/insert-user-orgs conn id ids)
    ins))

(defn- update-user [conn user {user-id :id :as existing}]
  (when (ec/update-user conn (merge existing (user->db user)))
    ;; Update user/org links
    (let [existing-org (set (eu/select-user-org-cuids conn user-id))
          new-org (set (:orgs user))
          to-add (cset/difference new-org existing-org)
          to-remove (cset/difference existing-org new-org)]
      (ec/insert-user-orgs conn user-id (eo/org-ids-by-cuids conn to-add))
      (when-not (empty? to-remove)
        (ec/delete-user-orgs conn [:in :org-id (eo/org-ids-by-cuids conn to-remove)]))
      user)))

(defn upsert-user [conn user]
  (if-let [existing (ec/select-user conn (ec/by-cuid (:id user)))]
    (update-user conn user existing)
    (insert-user conn user)))

(defn- select-user-by-filter [conn f]
  (when-let [r (ec/select-user conn f)]
    (let [org (eu/select-user-org-cuids conn (:id r))]
      (cond-> (db->user r)
        true (sc/drop-nil)
        (not-empty org) (assoc :orgs org)))))

(defn select-user-by-type [conn [type type-id]]
  (select-user-by-filter conn [:and
                               [:= :type type]
                               [:= :type-id type-id]]))

(defn select-user [st id]
  (select-user-by-filter (sc/get-conn st) (ec/by-cuid id)))

(defn select-user-orgs [st id]
  (->> (eu/select-user-orgs (sc/get-conn st) id)
       (map so/db->org-with-repos)))

(defn select-emails [st]
  (->> (eu/select-user-emails (sc/get-conn st))
       (map :email)))

(defn count-users [st]
  (ec/count-entities (sc/get-conn st) :users))

(defn delete-user [st id]
  (pos? (ec/delete-entities (sc/get-conn st) :users (ec/by-cuid id))))
