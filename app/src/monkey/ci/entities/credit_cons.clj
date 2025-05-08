(ns monkey.ci.entities.credit-cons
  (:require [monkey.ci.entities.core :as ec]))

(def basic-query
  {:select [:cc.*
            [:c.cuid :org-cuid]
            [:r.display-id :repo-did]
            [:b.display-id :build-did]
            [:cred.cuid :cred-cuid]]
   :from [[:credit-consumptions :cc]]
   :join [[:builds :b] [:= :b.id :cc.build-id]
          [:repos :r] [:= :r.id :b.repo-id]
          [:orgs :c] [:= :c.id :r.org-id]
          [:org-credits :cred] [:= :cred.id :cc.credit-id]]})

(defn select-credit-cons [conn f]
  (->> (assoc basic-query :where f)
       (ec/select conn)
       (map ec/convert-credit-cons-select)
       (map (fn [r]
              (-> r
                  (dissoc :cuid :org-cuid :repo-did :build-did :cred-cuid)
                  (assoc :id (:cuid r)
                         :org-id (:org-cuid r)
                         :repo-id (:repo-did r)
                         :build-id (:build-did r)
                         :credit-id (:cred-cuid r)))))))

(defn by-cuid [id]
  [:= :cc.cuid id])

(defn by-org [id]
  [:= :c.cuid id])

(defn since [ts]
  [:>= :cc.consumed-at (ec/->ts ts)])

(defn by-org-since [id ts]
  [:and (by-org id) (since ts)])
