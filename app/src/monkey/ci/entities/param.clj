(ns monkey.ci.entities.param
  "Build parameters queries"
  (:require [honey.sql :as sql]
            [medley.core :as mc]
            [monkey.ci.entities.core :as ec]))

(defn select-params-for-customer-cuid [conn cust-cuid]
  (ec/select conn
             {:select [:p.*]
              :from [[:customer-params :p]]
              :join [[:customers :c] [:= :c.id :p.customer-id]]
              :where [:= :c.cuid cust-cuid]}))

(defn select-customer-params-with-values
  "Selects all customer parameters and their values for the given customer."
  [conn cust-cuid]
  (let [params (select-params-for-customer-cuid conn cust-cuid)
        ->param-vals (fn [pv]
                       (select-keys pv [:name :value]))
        ->params (fn [pvs]
                   (map (fn [p]
                          (-> (select-keys p [:description :label-filters])
                              (assoc :id (:cuid p)
                                     :customer-id cust-cuid
                                     :parameters (map ->param-vals (get pvs (:id p))))
                              (ec/convert-label-filters-select)
                              (as-> x (mc/remove-vals nil? x))))
                        params))]
    (->> {:select [:pv.*]
          :from [[:customer-param-values :pv]]
          :join [[:customer-params :cp] [:= :cp.id :pv.params-id]]
          :where [:in :cp.id (map :id params)]}
         (ec/select conn)
         (group-by :params-id)
         (->params))))
