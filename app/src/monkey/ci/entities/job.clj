(ns monkey.ci.entities.job
  (:require [monkey.ci.entities.core :as ec]))

(defn select-by-sid [conn [org-cuid repo-id build-id job-id]]
  (some->> {:select [[:j.*]
                     [:c.cuid :org-cuid]
                     [:r.display-id :repo-display-id]
                     [:b.display-id :build-display-id]]
            :from [[:jobs :j]]
            :join [[:builds :b] [:= :b.id :j.build-id]
                   [:repos :r] [:= :r.id :b.repo-id]
                   [:orgs :c] [:= :c.id :r.org-id]]
            :where [:and
                    [:= :c.cuid org-cuid]
                    [:= :r.display-id repo-id]
                    [:= :b.display-id build-id]
                    [:= :j.display-id job-id]]}
           (ec/select conn)
           first
           (ec/convert-job-select)))

