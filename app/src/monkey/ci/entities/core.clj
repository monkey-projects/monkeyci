(ns monkey.ci.entities.core
  "Core functionality for database entities.  Allows to store/retrieve basic entities."
  (:require [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as sql]]))

(defn- maybe-set-uuid [x]
  (cond-> x
    (nil? (:uuid x)) (assoc :uuid (random-uuid))))

(def default-opts {:builder-fn rs/as-unqualified-kebab-maps})

(def insert-opts (assoc default-opts :return-keys true))

(defn insert-entity [{:keys [ds sql-opts]} table rec]
  (merge rec (jdbc/execute-one! ds
                                (h/format {:insert-into table
                                           :columns (keys rec)
                                           :values [(vals rec)]}
                                          sql-opts)
                                insert-opts)))

(def update-opts default-opts)

(defn- execute-update [{:keys [ds sql-opts]} query]
  (-> (jdbc/execute-one! ds
                         (h/format query
                                   sql-opts)
                         update-opts)
      ::jdbc/update-count))

(defn update-entity
  "Updates entity by id, returns the number of records updated (should be either 0 or 1)."
  [conn table obj]
  (execute-update conn {:update table
                        :set obj
                        :where [:= :id (:id obj)]}))

(def select-opts default-opts)

(defn select-entities
  "Selects entity from table using filter"
  [{:keys [ds sql-opts]} table f]
  (jdbc/execute! ds
                 (h/format {:select :*
                            :from [table]
                            :where f}
                           sql-opts)
                 select-opts))

(defn select-entity [conn table f]
  (first (select-entities conn table f)))

(defn delete-entities
  "Deletes all entities in the table the match filter `f`"
  [conn table f]
  (execute-update conn {:delete []
                        :from table
                        :where f}))

;;; Selection filters

(defn by-id [id]
  [:= :id id])

(defn by-uuid [uuid]
  [:= :uuid uuid])

(defn by-customer [id]
  [:= :customer-id id])

(defn by-repo [id]
  [:= :repo-id id])

;;; Customers

(defn insert-customer [conn cust]
  (insert-entity conn :customers (maybe-set-uuid cust)))

(defn update-customer [conn cust]
  (update-entity conn :customers cust))

(defn select-customer [conn f]
  (select-entity conn :customers f))

;;; Repositories

(defn insert-repo [conn repo]
  (insert-entity conn :repos (maybe-set-uuid repo)))

(defn update-repo [conn repo]
  (update-entity conn :repos repo))

(defn select-repo [conn f]
  (select-entity conn :repos f))

;;; Repository labels

(defn insert-repo-label [conn lbl]
  (insert-entity conn :repo-labels lbl))

(defn update-repo-label [conn lbl]
  (update-entity conn :repo-labels lbl))

(defn delete-repo-labels [conn f]
  (delete-entities conn :repo-labels f))

(defn select-repo-labels [conn f]
  (select-entities conn :repo-labels f))

;;; Customer parameters

(defn insert-customer-param [conn param]
  (insert-entity conn :customer-params (maybe-set-uuid param)))

(defn update-customer-param [conn param]
  (update-entity conn :customer-params param))

(defn delete-customer-params [conn f]
  (delete-entities conn :customer-params f))

(defn select-customer-params [conn f]
  (select-entities conn :customer-params f))
