(ns monkey.ci.entities.repo
  "Repository specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]]))

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
  "Finds the repository id given a org cuid and repo display id."
  [conn org-cuid display-id]
  (-> (ec/select conn
                 {:select [:r.id]
                  :from [[:repos :r]]
                  :join [[:orgs :c] [:= :c.id :r.org-id]]
                  :where [:and
                          [:= :c.cuid org-cuid]
                          [:= :r.display-id display-id]]})
      (first)
      :id))

(defn repo-display-ids [conn org-id]
  (->> (ec/select conn
                  {:select [:r.display-id]
                   :from [[:repos :r]]
                   :join [[:orgs :c] [:= :c.id :r.org-id]]
                   :where [:= :c.cuid org-id]})
       (map :display-id)))

(defn- select-repo-idx [conn org-id repo-id]
  (->> (ec/select conn
                  {:select [:ri.*]
                   :from [[:repo-indices :ri]]
                   :for :update
                   :join [[:repos :r] [:= :r.id :ri.repo-id]
                          [:orgs :c] [:= :r.org-id :c.id]]
                   :where [:and
                           [:= :c.cuid org-id]
                           [:= :r.display-id repo-id]]})
       (first)))

(defn next-repo-idx
  "Retrieves next repo index, and updates the repo-indices table accordingly.
   The repo-idx record is fetched for update, so it should work atomically."
  [conn org-id repo-id]
  (if-let [m (select-repo-idx conn org-id repo-id)]
    (do
      (ec/update-repo-idx conn (update m :next-idx inc))
      (:next-idx m))
    ;; No match found, create one using the current max build idx
    (let [id (repo-for-build-sid conn org-id repo-id)
          next-idx (inc (or (eb/select-max-idx conn org-id repo-id) 0))]
      (ec/insert-repo-idx conn {:repo-id id
                                :next-idx (inc next-idx)})
      next-idx)))
