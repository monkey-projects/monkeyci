(ns monkey.ci.entities.build
  "Custom queries for builds"
  (:require [monkey.ci.entities.core :as ec]))

(def basic-query
  {:select [:b.*]
   :from [[:builds :b]]
   :join [[:repos :r] [:= :r.id :b.repo-id]
          [:customers :c] [:= :c.id :r.customer-id]]})

(defn- build-query
  [cust-cuid repo-id]
  (assoc basic-query
         :where [:and
                 [:= :c.cuid cust-cuid]
                 [:= :r.display-id repo-id]]))

(defn select-builds-for-repo [conn cust-cuid repo-id]
  (->> (ec/select conn (build-query cust-cuid repo-id))
       (map ec/convert-build-select)))

(defn select-build-ids-for-repo [conn cust-cuid repo-id]
  (->> (ec/select conn (-> (build-query cust-cuid repo-id)
                           (assoc :select [:b.display-id])))
       (map :display-id)))

(defn select-build-by-sid
  "Finds build for customer, repo and display id"
  [conn cust-cuid repo-id build-id]
  (-> (ec/select conn
                 (-> (build-query cust-cuid repo-id)
                     (update :where conj [:= :b.display-id build-id])))
      (first)
      (ec/convert-build-select)))

#_(defn select-customer-cuid-and-repo-id [conn repo-id]
  (-> (ec/select conn
                 {:select [[:c.cuid :customer-id]
                           [:r.display-id :repo-id]]
                  :from [[:repos :r]]
                  :join [[:customers :c] [:= :c.id :r.customer-id]]
                  :where [:= :r.id repo-id]})
      (first)))

(defn select-max-idx [conn cust-cuid repo-id]
  (-> (ec/select conn
                 (-> (build-query cust-cuid repo-id)
                     (assoc :select [[:%max.idx :last]])))
      first
      :last))

(defn select-builds-for-customer-since [conn cust-cuid ts]
  (->> (ec/select conn (assoc basic-query
                              :where [:and
                                      [:= :c.cuid cust-cuid]
                                      [:>= :b.start-time (ec/->ts ts)]]))
       (map ec/convert-build-select)))
