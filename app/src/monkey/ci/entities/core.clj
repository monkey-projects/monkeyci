(ns monkey.ci.entities.core
  "Core functionality for database entities.  Allows to store/retrieve basic entities."
  (:require [cheshire.core :as json]
            [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [medley.core :as mc]
            [monkey.ci.entities.types]
            [next.jdbc :as jdbc]
            [next.jdbc
             [result-set :as rs]
             [sql :as sql]]))

(defn- maybe-set-uuid [x]
  (cond-> x
    (nil? (:uuid x)) (assoc :uuid (random-uuid))))

(def default-opts {:builder-fn rs/as-unqualified-kebab-maps})

(def insert-opts (assoc default-opts :return-keys true))

(def extract-id (some-fn :generated-key :id))

(defn insert-entity [{:keys [ds sql-opts]} table rec]
  (->> (jdbc/execute-one! ds
                          (h/format {:insert-into table
                                     :columns (keys rec)
                                     :values [(vals rec)]}
                                    sql-opts)
                          insert-opts)
       extract-id
       (assoc rec :id)))

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
                      :before-update identity
                      :after-select identity}
        [bi bu as] (map #(maybe-comp % extra-opts opts default-opts)
                        [:before-insert :before-update :after-select])]
    (intern *ns* (symbol (str "insert-" (name n)))
            (fn [conn e]
              (insert-entity conn (keyword pl) (bi e))))
    (intern *ns* (symbol (str "update-" (name n)))
            (fn [conn e]
              (update-entity conn (keyword pl) (bu e))))
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
  `(declare-entity-cruds ~(str n) {:before-insert maybe-set-uuid} ~opts))

(defmacro defaggregate
  "Declares functions that are used to fetch or manipulate entities that depend on
   others (i.e. that do not have their own uuid)."
  [n & [opts]]
  `(declare-entity-cruds ~(str n) {} ~opts))

;;; Selection filters

(defn by-id [id]
  [:= :id id])

(defn by-uuid [uuid]
  [:= :uuid uuid])

(defn by-customer [id]
  [:= :customer-id id])

(defn by-repo [id]
  [:= :repo-id id])

(defn by-ssh-key [id]
  [:= :ssh-key-id id])

(defn by-param [id]
  [:= :param-id id])

(defn by-user [id]
  [:= :user-id id])

;;; Basic entities

(defentity customer)
(defentity repo)
(defentity customer-param)
(defentity webhook)
(defentity ssh-key)
(defentity user)

(defn jobs->json [b]
  (mc/update-existing b :jobs json/generate-string))

(defn json->jobs [b]
  (mc/update-existing b :jobs #(json/parse-string % keyword)))

(defentity build {:before-insert jobs->json
                  :before-update jobs->json
                  :after-select  json->jobs})

;;; Aggregate entities

(defaggregate repo-label)
(defaggregate param-label)
(defaggregate ssh-key-label)
(defaggregate user-customer)
