(ns monkey.ci.entities.token
  "SQL functions for user and org tokens"
  (:require [monkey.ci.entities.core :as ec]))

(defn- select-tokens [conn table owner-table owner-key owner-id]
  (letfn [(convert [r]
            (-> r
                (ec/convert-token)
                (assoc owner-key (:owner-cuid r))))]
    (->> {:select [:t.* [:o.cuid :owner-cuid]]
          :from [[table :t]]
          :join [[owner-table :o] [:= :o.id (keyword (str "t." (name owner-key)))]]
          :where [[:= :o.cuid owner-id]]}
         (ec/select conn)
         (map convert))))

(defn select-user-tokens [conn user-id]
  (select-tokens conn :user-tokens :users :user-id user-id))

(defn select-org-tokens [conn org-id]
  (select-tokens conn :org-tokens :orgs :org-id org-id))
