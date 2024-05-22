(ns monkey.ci.entities.core
  "Core functionality for database entities.  Allows to store/retrieve basic entities."
  (:require [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [edn :as edn]]
            [monkey.ci.entities.types]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as sql]]))

(defn- maybe-set-cuid [x]
  (cond-> x
    (nil? (:cuid x)) (assoc :cuid (cuid/random-cuid))))

(def default-opts {:builder-fn rs/as-unqualified-kebab-maps})

(def insert-opts (assoc default-opts :return-keys true))

(def extract-id (some-fn :generated-key :id))

(defn insert-entities
  "Batch inserts multiple entities at once.  The records are assumed to
   be vectors of values."
  [{:keys [ds sql-opts]} table cols recs]
  (->> (jdbc/execute! ds
                      (h/format {:insert-into table
                                 :columns cols
                                 :values recs}
                                sql-opts)
                      insert-opts)
       (map extract-id)
       (zipmap recs)
       (map (fn [[r id]]
              (-> (zipmap cols r)
                  (assoc :id id))))))

(defn insert-entity [{:keys [ds sql-opts] :as conn} table rec]
  ;; Both work, maybe the first is a little bit more efficient.
  #_(->> (jdbc/execute-one! ds
                            (h/format {:insert-into table
                                       :columns (keys rec)
                                       :values [(vals rec)]}
                                      sql-opts)
                            insert-opts)
         extract-id
         (assoc rec :id))
  (-> (insert-entities conn table (keys rec) [(vals rec)])
      (first)))

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

(defn select
  "Formats and executes the given query"
  [{:keys [ds sql-opts]} query]
  (jdbc/execute! ds (h/format query sql-opts) select-opts))

(defn select-entities
  "Selects entity from table using filter"
  [conn table f]
  (select conn {:select :*
                :from [table]
                :where f}))

(defn select-entity [conn table f]
  (first (select-entities conn table f)))

(defn delete-entities
  "Deletes all entities in the table the match filter `f`"
  [conn table f]
  (execute-update conn {:delete []
                        :from table
                        :where f}))

(defn- maybe-comp
  "Takes functions stored at `k` in the maps, and composes them left to right."
  [k & maps]
  (->> (map k maps)
       (remove nil?)
       (apply comp)))

(defn- declare-entity-cruds
  "Declares basic functions used for CRUD and simple selects."
  [n opts extra-opts]
  (let [pl (str (name n) "s")
        default-opts {:before-insert identity
                      :after-insert  identity
                      :before-update identity
                      :after-update  identity
                      :after-select  identity}
        [bi ai bu au as] (map #(maybe-comp % extra-opts opts default-opts)
                              [:before-insert :after-insert
                               :before-update :after-update
                               :after-select])]
    (intern *ns* (symbol (str "insert-" (name n)))
            (fn [conn e]
              (first (ai [(insert-entity conn (keyword pl) (bi e)) e]))))
    (intern *ns* (symbol (str "update-" (name n)))
            (fn [conn e]
              (first (au [(update-entity conn (keyword pl) (bu e)) e]))))
    (intern *ns* (symbol (str "delete-" pl))
            (fn [conn f]
              (delete-entities conn (keyword pl) f)))
    (intern *ns* (symbol (str "select-" (name n)))
            (fn [conn f]
              (some-> (select-entity conn (keyword pl) f)
                      (as))))
    (intern *ns* (symbol (str "select-" pl))
            (fn [conn f]
              (->> (select-entities conn (keyword pl) f)
                   (map as))))))

(defmacro defentity
  "Declares functions that can be used to fetch or manipulate a basic entity in db."
  [n & [opts]]
  `(declare-entity-cruds ~(str n) {:before-insert maybe-set-cuid} ~opts))

(defmacro defaggregate
  "Declares functions that are used to fetch or manipulate entities that depend on
   others (i.e. that do not have their own cuid)."
  [n & [opts]]
  `(declare-entity-cruds ~(str n) {} ~opts))

;;; Selection filters

(defn by-id [id]
  [:= :id id])

(defn by-cuid [cuid]
  [:= :cuid cuid])

(defn by-customer [id]
  [:= :customer-id id])

(defn by-repo [id]
  [:= :repo-id id])

(defn by-build [id]
  [:= :build-id id])

(defn by-ssh-key [id]
  [:= :ssh-key-id id])

(defn by-param [id]
  [:= :param-id id])

(defn by-user [id]
  [:= :user-id id])

(defn by-display-id [id]
  [:= :display-id id])

;;; Basic entities

(defentity customer)
(defentity repo)
(defentity webhook)
(defentity user)

(defn- or-nil [f]
  (fn [x]
    (when x
      (f x))))

(defn- time->int [k x]
  (mc/update-existing x k (or-nil (memfn getTime))))

(defn- int->time [k x]
  (mc/update-existing x k (or-nil #(java.sql.Timestamp. %))))

(def start-time->int (partial time->int :start-time))
(def int->start-time (partial int->time :start-time))

(def end-time->int (partial time->int :start-time))
(def int->end-time (partial int->time :end-time))

(defn- status->str [x]
  (mc/update-existing x :status name))

(defn- str->status [x]
  (mc/update-existing x :status keyword))

(def prepare-timed (comp status->str int->start-time int->end-time))
;; Seems like timestamps are read as long?  So no need to convert back.
(def convert-timed str->status)

(defentity build {:before-insert prepare-timed
                  :after-insert  convert-timed
                  :before-update prepare-timed
                  :after-update  convert-timed
                  :after-select  convert-timed})

(defn- prop->edn [prop b]
  (mc/update-existing b prop (or-nil edn/->edn)))

(defn- edn->prop [prop b]
  (mc/update-existing b prop (or-nil edn/edn->)))

(defn- copy-prop [prop [r e]]
  [(assoc r prop (prop e)) e])

(def details->edn (partial prop->edn :details))
(def edn->details (partial edn->prop :details))
(def details->job (partial copy-prop :details))

(def prepare-job (comp details->edn prepare-timed))
(def convert-job (comp details->job convert-timed))
(def convert-job-select (comp edn->details convert-timed))

(defentity job
  {:before-insert prepare-job
   :after-insert  convert-job
   :before-update prepare-job
   :after-update  convert-job
   :after-select  convert-job-select})

;; For customer params and ssh keys we don't store the labels in a separate table.
;; This to avoid lots of work on mapping to and from sql statements, and because
;; there is no real added value: it's not possible to do a query that will retrieve
;; all matching params or ssh keys, so we may just as well do it in code.

(def prepare-label-filters (partial prop->edn :label-filters))
(def convert-label-filters (partial copy-prop :label-filters))
(def convert-label-filters-select (partial edn->prop :label-filters))

(def label-filter-conversions
  {:before-insert prepare-label-filters
   :after-insert  convert-label-filters
   :before-update prepare-label-filters
   :after-update  convert-label-filters
   :after-select  convert-label-filters-select})

(defentity customer-param label-filter-conversions)
(defentity ssh-key label-filter-conversions)

;;; Aggregate entities

(defaggregate repo-label)

(defn insert-repo-labels
  "Batch inserts multiple labels at once"
  [conn labels]
  (->> labels
       (map (juxt :repo-id :name :value))
       (insert-entities conn :repo-labels [:repo-id :name :value])))

(defaggregate user-customer)
(defaggregate customer-param-value)
