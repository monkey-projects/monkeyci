(ns monkey.ci.entities.param
  "Build parameters queries"
  (:require [honey.sql :as sql]
            [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn- select-params-with-customer [conn f]
  (ec/select conn
             {:select [:p.* [:c.cuid :customer-cuid]]
              :from [[:customer-params :p]]
              :join [[:customers :c] [:= :c.id :p.customer-id]]
              :where f}))

(defn- select-params-with-values
  "Selects parameters and their values using the filter"
  [conn f]
  (let [params (select-params-with-customer conn f)
        ->param-vals (fn [pv]
                       (select-keys pv [:name :value]))
        ->params (fn [pvs]
                   (map (fn [p]
                          (-> (select-keys p [:description :label-filters])
                              (assoc :id (:cuid p)
                                     :customer-id (:customer-cuid p)
                                     :parameters (map ->param-vals (get pvs (:id p))))
                              (ec/convert-label-filters-select)
                              (as-> x (mc/remove-vals nil? x))))
                        params))]
    (when-not (empty? params)
      (->> {:select [:pv.*]
            :from [[:customer-param-values :pv]]
            :join [[:customer-params :cp] [:= :cp.id :pv.params-id]]
            :where [:in :cp.id (map :id params)]}
           (ec/select conn)
           (group-by :params-id)
           (->params)))))

(defn select-customer-params-with-values
  "Selects all customer parameters and their values for the given customer."
  [conn cust-cuid]
  (select-params-with-values conn [:= :c.cuid cust-cuid]))

(defn select-param-with-values
  "Selects a single parameter set by cuid"
  [conn param-cuid]
  (-> (select-params-with-values conn [:= :p.cuid param-cuid])
      (first)))
