(ns monkey.ci.entities.job
  (:require [monkey.ci.entities.core :as ec]))

(defn select-by-sid [conn [org-cuid repo-id build-id job-id]]
  (some->> {:select [[:j.*]
                     [:o.cuid :org-cuid]
                     [:r.display-id :repo-display-id]
                     [:b.display-id :build-display-id]]
            :from [[:jobs :j]]
            :join [[:builds :b] [:= :b.id :j.build-id]
                   [:repos :r] [:= :r.id :b.repo-id]
                   [:orgs :o] [:= :o.id :r.org-id]]
            :where [:and
                    [:= :o.cuid org-cuid]
                    [:= :r.display-id repo-id]
                    [:= :b.display-id build-id]
                    [:= :j.display-id job-id]]}
           (ec/select conn)
           first
           (ec/convert-job-select)))

(defn select-events [conn [org-cuid repo-id build-id job-id]]
  (->> {:select [[:e.*]
                 [:j.display-id :job-display-id]
                 [:o.cuid :org-cuid]
                 [:r.display-id :repo-display-id]
                 [:b.display-id :build-display-id]]
        :from [[:job-events :e]]
        :join [[:jobs :j] [:= :j.id :e.job-id]
               [:builds :b] [:= :b.id :j.build-id]
               [:repos :r] [:= :r.id :b.repo-id]
               [:orgs :o] [:= :o.id :r.org-id]]
        :where [:and
                [:= :o.cuid org-cuid]
                [:= :r.display-id repo-id]
                [:= :b.display-id build-id]
                [:= :j.display-id job-id]]
        :order-by [:e.time]}
       (ec/select conn)
       (map ec/convert-job-evt-select)))
