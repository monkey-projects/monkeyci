(ns monkey.ci.entities.user
  (:require [monkey.ci.entities.core :as ec]))

(defn select-user-org-cuids
  "Fetches all orgs cuids linked to a user"
  [conn user-id]
  (->> (ec/select conn
                  {:select [:c.cuid]
                   :from [[:orgs :c]]
                   :join [[:user-orgs :uc] [:= :uc.org-id :c.id]]
                   :where [:= :uc.user-id user-id]})
       (map :cuid)))

(defn select-user-orgs
  "Selects all orgs linked to this user.  This is similar to searching
   for orgs, so repositories are not fetched."
  [conn user-cuid]
  (ec/select conn
             {:select [:c.*]
              :from [[:orgs :c]]
              :join [[:user-orgs :uc] [:= :uc.org-id :c.id]
                     [:users :u] [:= :u.id :uc.user-id]]
              :where [:= :u.cuid user-cuid]}))

(defn select-sysadmin-by-user-cuid [conn cuid]
  (->> (ec/select conn
                  {:select [:s.*]
                   :from [[:sysadmins :s]]
                   :join [[:users :u] [:= :u.id :s.user-id]]
                   :where [:= :u.cuid cuid]})
       (first)))

(defn select-user-emails [conn]
  (ec/select conn
             {:select [:u.email]
              :from [[:users :u]]
              :where [:is-not :u.email nil]}))

(defn select-user-setting-by-cuid [conn cuid]
  (->> {:select [:s.*]
        :from [[:user-settings :s]]
        :join [[:users :u] [:= :u.id :s.user-id]]
        :where [:= :u.cuid cuid]}
       (ec/select conn)
       first))
