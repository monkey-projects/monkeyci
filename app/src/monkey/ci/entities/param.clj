(ns monkey.ci.entities.param
  "Build parameters queries"
  (:require [honey.sql :as sql]
            [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn- select-params-with-org [conn f]
  (ec/select conn
             {:select [:p.* [:c.cuid :org-cuid]]
              :from [[:org-params :p]]
              :join [[:orgs :c] [:= :c.id :p.org-id]]
              :where f}))

(defn- select-params-with-values
  "Selects parameters and their values using the filter"
  [conn f]
  (let [params (select-params-with-org conn f)
        ->param-vals (fn [pv]
                       (select-keys pv [:name :value]))
        ->params (fn [pvs]
                   (map (fn [p]
                          (-> (select-keys p [:description :label-filters :dek])
                              (assoc :id (:cuid p)
                                     :org-id (:org-cuid p)
                                     :parameters (map ->param-vals (get pvs (:id p))))
                              (ec/convert-label-filters-select)
                              (as-> x (mc/remove-vals nil? x))))
                        params))]
    (when-not (empty? params)
      (->> {:select [:pv.*]
            :from [[:org-param-values :pv]]
            :join [[:org-params :cp] [:= :cp.id :pv.params-id]]
            :where [:in :cp.id (map :id params)]}
           (ec/select conn)
           (group-by :params-id)
           (->params)))))

(defn select-org-params-with-values
  "Selects all org parameters and their values for the given org."
  [conn org-cuid]
  (select-params-with-values conn [:= :c.cuid org-cuid]))

(defn select-param-with-values
  "Selects a single parameter set by cuid"
  [conn param-cuid]
  (-> (select-params-with-values conn [:= :p.cuid param-cuid])
      (first)))
