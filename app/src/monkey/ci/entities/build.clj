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

(defn select-latest-builds
  "Fetches all latest for all repos for a customer"
  [conn cust-cuid]
  (->> (ec/select conn
                  (-> basic-query
                      (assoc :select [:b.* [:r.display-id :repo-display-id] [:c.cuid :customer-cuid]]
                             :where [:= :c.cuid cust-cuid]
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

(defn select-runner-details [conn f]
  (some->> {:select [:rd.*]
            :from [[:build-runner-details :rd]]
            :join [[:builds :b] [:= :b.id :rd.build-id]
                   [:repos :r] [:= :r.id :b.repo-id]
                   [:customers :c] [:= :c.id :r.customer-id]]
            :where f}
           (ec/select conn)
           (first)
           (ec/convert-runner-details-select)))

(defn by-build-sid [[cust-id repo-id build-id]]
  [:and
   [:= :c.cuid cust-id]
   [:= :r.display-id repo-id]
   [:= :b.display-id build-id]])
