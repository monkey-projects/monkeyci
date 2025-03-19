(ns monkey.ci.entities.repo
  "Repository specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn repos-with-labels
  "Selects repositories with labels according to filter `f`."
  [conn f]
  (let [repos (ec/select-repos conn f)
        labels (when (not-empty repos)
                 (->> repos
                      (map :id)
                      (distinct)
                      (vector :in :repo-id)
                      (ec/select-repo-labels conn)
                      (group-by :repo-id)))]
    (map #(mc/assoc-some % :labels (get labels (:id %))) repos)))

(defn repo-for-build-sid
  "Finds the repository id given a customer cuid and repo display id."
  [conn cust-cuid display-id]
  (-> (ec/select conn
                 {:select [:r.id]
                  :from [[:repos :r]]
                  :join [[:customers :c] [:= :c.id :r.customer-id]]
                  :where [:and
                          [:= :c.cuid cust-cuid]
                          [:= :r.display-id display-id]]})
      (first)
      :id))

(defn repo-display-ids [conn cust-id]
  (->> (ec/select conn
                  {:select [:r.display-id]
                   :from [[:repos :r]]
                   :join [[:customers :c] [:= :c.id :r.customer-id]]
                   :where [:= :c.cuid cust-id]})
       (map :display-id)))

(defn- select-repo-idx [conn cust-id repo-id]
  (->> (ec/select conn
                  {:select [:ri.*]
                   :from [[:repo-indices :ri]]
                   :for :update
                   :join [[:repos :r] [:= :r.id :ri.repo-id]
                          [:customers :c] [:= :r.customer-id :c.id]]
                   :where [:and
                           [:= :c.cuid cust-id]
                           [:= :r.display-id repo-id]]})
       (first)))

(defn next-repo-idx
  "Retrieves next repo index, and updates the repo-indices table accordingly.
   The repo-idx record is fetched for update, so it should work atomically."
  [conn cust-id repo-id]
  (if-let [m (select-repo-idx conn cust-id repo-id)]
    (do
      (ec/update-repo-idx conn (update m :next-idx inc))
      (:next-idx m))
    ;; No match found, create one
    (let [id (repo-for-build-sid conn cust-id repo-id)]
      (ec/insert-repo-idx conn {:repo-id id
                                :next-idx 2})
      1)))
