(ns monkey.ci.entities.build
  "Custom queries for builds"
  (:require [monkey.ci.entities.core :as ec]))

(def basic-query
  {:select [:b.*]
   :from [[:builds :b]]
   :join [[:repos :r] [:= :r.id :b.repo-id]
          [:orgs :c] [:= :c.id :r.org-id]]})

(defn- build-query
  [org-cuid repo-id]
  (assoc basic-query
         :where [:and
                 [:= :c.cuid org-cuid]
                 [:= :r.display-id repo-id]]))

(defn select-builds-for-repo [conn org-cuid repo-id]
  (->> (ec/select conn (build-query org-cuid repo-id))
       (map ec/convert-build-select)))

(defn select-build-ids-for-repo [conn org-cuid repo-id]
  (->> (ec/select conn (-> (build-query org-cuid repo-id)
                           (assoc :select [:b.display-id])))
       (map :display-id)))

(defn select-build-by-sid
  "Finds build for org, repo and display id"
  [conn org-cuid repo-id build-id]
  (some-> (ec/select conn
                     (-> (build-query org-cuid repo-id)
                         (update :where conj [:= :b.display-id build-id])))
          (first)
          (ec/convert-build-select)))

(defn select-max-idx [conn org-cuid repo-id]
  (-> (ec/select conn
                 (-> (build-query org-cuid repo-id)
                     (assoc :select [[:%max.idx :last]])))
      first
      :last))

(defn select-builds-for-org-since [conn org-cuid ts]
  (->> (ec/select conn (assoc basic-query
                              :select [:b.* [:r.display-id :repo-display-id] [:c.cuid :org-cuid]]
                              :where [:and
                                      [:= :c.cuid org-cuid]
                                      [:>= :b.start-time (ec/->ts ts)]]))
       (map ec/convert-build-select)))

(defn select-latest-build [conn org-cuid repo-id]
  (some-> (ec/select conn (-> (build-query org-cuid repo-id)
                              (assoc :order-by [[:idx :desc]]
                                     :limit 1)))
          (first)
          (ec/convert-build-select)))

(defn select-latest-builds
  "Fetches all latest for all repos for a org"
  [conn org-cuid]
  (->> (ec/select conn
                  (-> basic-query
                      (assoc :select [:b.* [:r.display-id :repo-display-id] [:c.cuid :org-cuid]]
                             :where [:= :c.cuid org-cuid]
                             ;; Find the max idx using a group-by subselect.
                             ;; We use the idx instead of the start time for performance (since idx
                             ;; is indexed)
                             :inner-join [[{:select [[:%max.idx :idx]
                                                     [:sb.repo-id :repo-id]]
                                            :from [[:builds :sb]]
                                            :group-by [:sb.repo-id]} :latest]
                                          [:and
                                           [:= :latest.idx :b.idx]
                                           [:= :latest.repo-id :b.repo-id]]])))
       (map ec/convert-build-select)))

(defn select-latest-n-builds [conn org-cuid n]
  (->> (ec/select conn (-> basic-query
                           (assoc :select [:b.* [:r.display-id :repo-display-id] [:c.cuid :org-cuid]]
                                  :where [:= :c.cuid org-cuid]
                                  :order-by [[:start-time :desc]]
                                  :limit n)))
       (map ec/convert-build-select)))

(defn select-runner-details [conn f]
  (some->> {:select [:rd.*]
            :from [[:build-runner-details :rd]]
            :join [[:builds :b] [:= :b.id :rd.build-id]
                   [:repos :r] [:= :r.id :b.repo-id]
                   [:orgs :c] [:= :c.id :r.org-id]]
            :where f}
           (ec/select conn)
           (first)
           (ec/convert-runner-details-select)))

(defn by-build-sid [[org-id repo-id build-id]]
  [:and
   [:= :c.cuid org-id]
   [:= :r.display-id repo-id]
   [:= :b.display-id build-id]])
