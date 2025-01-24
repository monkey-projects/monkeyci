(ns monkey.ci.entities.job
  (:require [monkey.ci.entities.core :as ec]))

(defn select-by-sid [conn [cust-cuid repo-id build-id job-id]]
  (some->> {:select [[:j.*]
                     [:c.cuid :cust-cuid]
                     [:r.display-id :repo-display-id]
                     [:b.display-id :build-display-id]]
            :from [[:jobs :j]]
            :join [[:builds :b] [:= :b.id :j.build-id]
                   [:repos :r] [:= :r.id :b.repo-id]
                   [:customers :c] [:= :c.id :r.customer-id]]
            :where [:and
                    [:= :c.cuid cust-cuid]
                    [:= :r.display-id repo-id]
                    [:= :b.display-id build-id]
                    [:= :j.display-id job-id]]}
           (ec/select conn)
           first
           (ec/convert-job-select)))

(defn select-for-update
  "Selects a number of job records for updating"
  [conn f]
  (->> {:select :*
        :from [:jobs]
        :for :update
        :where f}
       (ec/select conn)
       (map ec/convert-job-select)))
