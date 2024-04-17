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

(defn insert-entity [{:keys [ds sql-opts]} table obj]
  (let [rec (maybe-set-uuid obj)]
    (merge rec (jdbc/execute-one! ds
                                  (h/format {:insert-into table
                                             :columns (keys rec)
                                             :values [(vals rec)]}
                                            sql-opts)
                                  insert-opts))))

(def update-opts default-opts)

(defn update-entity
  "Updates entity by id, returns the number of records updated (should be either 0 or 1)."
  [{:keys [ds sql-opts]} table obj]
  (-> (jdbc/execute-one! ds
                         (h/format {:update table
                                    :set obj
                                    :where [:= :id (:id obj)]}
                                   sql-opts)
                         update-opts)
      ::jdbc/update-count))

(def select-opts default-opts)

(defn select-entity
  "Selects entity from table using filter"
  [{:keys [ds sql-opts]} table f]
  (some->> (jdbc/execute! ds
                          (h/format {:select :*
                                     :from [table]
                                     :where f}
                                    sql-opts)
                          select-opts)
           (first)))

(defn by-id [id]
  [:= :id id])

(defn by-uuid [uuid]
  [:= :uuid uuid])

(defn insert-customer [conn cust]
  (insert-entity conn :customers cust))

(defn update-customer [conn cust]
  (update-entity conn :customers cust))

(defn select-customer [conn f]
  (select-entity conn :customers f))

(defn insert-repo [conn cust]
  (insert-entity conn :repos cust))

(defn select-repo [conn f]
  (select-entity conn :repos f))
