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
