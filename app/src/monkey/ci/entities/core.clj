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

(defn- declare-entity-cruds
  "Declares basic functions used for CRUD and simple selects."
  [n before-insert]
  (let [pl (str (name n) "s")]
    (intern *ns* (symbol (str "insert-" (name n)))
            (fn [conn e]
              (insert-entity conn (keyword pl) (before-insert e))))
    (intern *ns* (symbol (str "update-" (name n)))
            (fn [conn e]
              (update-entity conn (keyword pl) e)))
    (intern *ns* (symbol (str "delete-" pl))
            (fn [conn f]
              (delete-entities conn (keyword pl) f)))
    (intern *ns* (symbol (str "select-" (name n)))
            (fn [conn f]
              (select-entity conn (keyword pl) f)))
    (intern *ns* (symbol (str "select-" pl))
            (fn [conn f]
              (select-entities conn (keyword pl) f)))))

(defmacro defentity [n]
  `(declare-entity-cruds ~(str n) maybe-set-uuid))

(defmacro defaggregate [n]
  `(declare-entity-cruds ~(str n) identity))

;;; Selection filters

(defn by-id [id]
  [:= :id id])

(defn by-uuid [uuid]
  [:= :uuid uuid])

(defn by-customer [id]
  [:= :customer-id id])

(defn by-repo [id]
  [:= :repo-id id])

;;; Basic entities

(defentity customer)
(defentity repo)
(defaggregate repo-label)
(defentity customer-param)
(defentity webhook)
