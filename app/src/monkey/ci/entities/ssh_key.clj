(ns monkey.ci.entities.ssh-key
  (:require [honey.sql :as h]
            [monkey.ci.entities.core :as ec]))

(defn select-ssh-keys-as-entity [conn org-cuid]
  (->> (ec/select conn
                  {:select [[:k.cuid :id]
                            :k.private-key
                            :k.public-key
                            :k.description
                            :k.label-filters
                            :k.dek
                            [:c.cuid :org-id]]
                   :from [[:ssh-keys :k]
                          [:orgs :c]]
                   :where [:and
                           [:= :c.cuid org-cuid]
                           [:= :c.id :k.org-id]]})
       (map ec/convert-label-filters-select)))
