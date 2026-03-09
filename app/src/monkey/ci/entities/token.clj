(ns monkey.ci.entities.token
  "SQL functions for user and org tokens"
  (:require [monkey.ci.entities.core :as ec]))

(defn- select-tokens [conn table owner-table owner-key f]
  (letfn [(convert [r]
            (-> r
                (ec/convert-token)
                (assoc owner-key (:owner-cuid r))))]
    (->> {:select [:t.* [:o.cuid :owner-cuid]]
          :from [[table :t]]
          :join [[owner-table :o] [:= :o.id (keyword (str "t." (name owner-key)))]]
          :where f}
         (ec/select conn)
         (map convert))))

(defn by-owner [owner-id]
  [:= :o.cuid owner-id])

(defn by-token [token]
  [:= :t.token token])

(def by-org by-owner)
(def by-user by-owner)

(defn select-user-tokens [conn f]
  (select-tokens conn :user-tokens :users :user-id f))

(defn select-org-tokens [conn f]
  (select-tokens conn :org-tokens :orgs :org-id f))
