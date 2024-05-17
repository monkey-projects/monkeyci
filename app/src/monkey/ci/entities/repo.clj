(ns monkey.ci.entities.repo
  "Repository specific query functions"
  (:require [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn repos-with-labels
  "Selects repositories with labels according to filter `f`."
  [conn f]
  (let [repos (ec/select-repos conn f)
        labels (->> repos
                    (map :id)
                    (distinct)
                    (vector :in :repo-id)
                    (ec/select-repo-labels conn)
                    (group-by :repo-id))]
    (map #(mc/assoc-some % :labels (get labels (:id %))) repos)))
