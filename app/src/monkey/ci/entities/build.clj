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
  (some-> (ec/select conn
                     (-> (build-query cust-cuid repo-id)
                         (update :where conj [:= :b.display-id build-id])))
          (first)
          (ec/convert-build-select)))

(defn select-build-by-sid-for-update
  [conn cust-cuid repo-id build-id]
  (some-> (ec/select conn
                     (-> (build-query cust-cuid repo-id)
                         (update :where conj [:= :b.display-id build-id])
                         (assoc :for :update)))
          (first)
          (ec/convert-build-select)))

(defn select-max-idx [conn cust-cuid repo-id]
  (-> (ec/select conn
                 (-> (build-query cust-cuid repo-id)
                     (assoc :select [[:%max.idx :last]])))
      first
      :last))

(defn select-builds-for-customer-since [conn cust-cuid ts]
  (->> (ec/select conn (assoc basic-query
                              :select [:b.* [:r.display-id :repo-display-id] [:c.cuid :customer-cuid]]
                              :where [:and
                                      [:= :c.cuid cust-cuid]
                                      [:>= :b.start-time (ec/->ts ts)]]))
       (map ec/convert-build-select)))

(defn select-latest-build [conn cust-cuid repo-id]
  (some-> (ec/select conn (-> (build-query cust-cuid repo-id)
                              (assoc :order-by [[:idx :desc]]
                                     :limit 1)))
          (first)
          (ec/convert-build-select)))
