(ns monkey.ci.entities.core
  "Core functionality for database entities.  Allows to store/retrieve basic entities."
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [honey.sql :as h]
            [honey.sql.helpers :as hh]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [edn :as edn]
             [utils :as u]]
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
   be vectors of values.  Returns the input entities with their generated
   ids added."
  [{:keys [ds sql-opts]} table cols recs]
  (let [sql (h/format {:insert-into table
                       :columns cols
                       :values recs}
                      sql-opts)]
    (log/trace "Executing insert:" sql)
    ;; FIXME In MySQL, when there is no generated id (i.e. aggregate entities)
    ;; this returns `nil`, but it should return the entities instead.
    (->> (jdbc/execute! ds
                        sql
                        insert-opts)
         (map extract-id)
         (zipmap recs)
         (map (fn [[r id]]
                (-> (zipmap cols r)
                    (mc/assoc-some :id id)))))))

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
  (let [sql (h/format query sql-opts)]
    (log/trace "Executing update:" sql)
    (-> (jdbc/execute-one! ds sql update-opts)
        ::jdbc/update-count)))

(defn update-entity
  "Updates entity by id, returns the number of records updated (should be either 0 or 1)."
  ([conn table obj id-col]
   (execute-update conn {:update table
                         :set obj
                         :where [:= id-col (id-col obj)]}))
  ([conn table obj]
   (update-entity conn table obj :id)))

(def select-opts default-opts)

(defn select
  "Formats and executes the given query"
  [{:keys [ds sql-opts]} query]
  (let [sql (h/format query sql-opts)]
    (log/trace "Executing query:" sql)
    (jdbc/execute! ds sql select-opts)))

(defn select-entities
  "Selects entity from table using filter"
  [conn table f]
  (select conn (cond-> {:select :*
                        :from [table]}
                 f (assoc :where f))))

(defn select-entity [conn table f]
  (first (select-entities conn table f)))

(defn delete-entities
  "Deletes all entities in the table the match filter `f`"
  [conn table f]
  (execute-update conn {:delete []
                        :from table
                        :where f}))

(defn count-entities
  "Counts records in a table, with an optional filter"
  [conn table & [f]]
  (->> (cond-> {:select [[[:count :*] :c]]
                :from table}
         f (assoc :where f))
       (select conn)
       (first)
       :c))

(defn- maybe-comp
  "Takes functions stored at `k` in the maps, and composes them left to right."
  [k & maps]
  (->> (map k maps)
       (remove nil?)
       (apply comp)))

(defn- declare-entity-cruds
  "Declares basic functions used for CRUD and simple selects."
  [n opts extra-opts]
  (let [pl (or (:plural extra-opts)
               (:plural opts)
               (str (name n) "s"))
        table (or (:table extra-opts)
                  (:table opts)
                  (keyword pl))
        default-opts {:before-insert identity
                      :after-insert  identity
                      :before-update identity
                      :after-update  identity
                      :after-select  identity}
        [bi ai bu au as] (map #(maybe-comp % extra-opts opts default-opts)
                              [:before-insert :after-insert
                               :before-update :after-update
                               :after-select])
        id-col (get extra-opts :id-col :id)]
    (intern *ns* (symbol (str "insert-" (name n)))
            (fn [conn e]
              (first (ai [(insert-entity conn table (bi e)) e]))))
    (intern *ns* (symbol (str "update-" (name n)))
            (fn [conn e]
              (let [upd (bu e)]
                (when (pos? (update-entity conn table upd id-col))
                  (first (au [upd e]))))))
    (intern *ns* (symbol (str "delete-" pl))
            (fn [conn f]
              (delete-entities conn table f)))
    (intern *ns* (symbol (str "select-" (name n)))
            (fn [conn f]
              (some-> (select-entity conn table f)
                      (as))))
    (intern *ns* (symbol (str "select-" pl))
            (fn [conn f]
              (->> (select-entities conn table f)
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

(defn by-org [id]
  [:= :org-id id])

(def ^:deprecated by-customer by-org)

(defn by-repo [id]
  [:= :repo-id id])

(defn by-build [id]
  [:= :build-id id])

(defn by-ssh-key [id]
  [:= :ssh-key-id id])

(defn by-params [id]
  [:= :params-id id])

(defn by-user [id]
  [:= :user-id id])

(defn by-display-id [id]
  [:= :display-id id])

(defprotocol EpochConvertable
  (->epoch [x]))

(extend-protocol EpochConvertable
  java.sql.Timestamp
  (->epoch [ts]
    (.getTime ts))
  java.sql.Date
  (->epoch [ts]
    (.getTime ts))
  java.time.Instant
  (->epoch [i]
    (.toEpochMilli i))
  java.lang.Long
  (->epoch [l]
    l))

(defn- time->int [k x]
  (mc/update-existing x k (u/or-nil ->epoch)))

(defn ->ts [s]
  (when s
    (java.sql.Timestamp. s)))

(defn- int->time [k x]
  (mc/update-existing x k ->ts))

(def start-time->int (partial time->int :start-time))
(def int->start-time (partial int->time :start-time))

(def end-time->int (partial time->int :end-time))
(def int->end-time (partial int->time :end-time))

(defn- serialize-keyword [k]
  (when k
    (->> k
         ((juxt namespace name))
         (remove nil?)
         (cs/join "/"))))

(defn- keyword->str [k x]
  (mc/update-existing x k serialize-keyword))

(defn- str->keyword [k x]
  (mc/update-existing x k (u/or-nil keyword)))

(def status->str (partial keyword->str :status))
(def str->status (partial str->keyword :status))

(def source->str (partial keyword->str :source))
(def str->source (partial str->keyword :source))

(def prepare-timed (comp status->str int->start-time int->end-time))
(def convert-timed (comp str->status start-time->int end-time->int))

(defn- prop->edn [prop b]
  (mc/update-existing b prop (u/or-nil edn/->edn)))

(defn- edn->prop [prop b]
  (mc/update-existing b prop (u/or-nil edn/edn->)))

(defn- copy-prop [prop [r e]]
  [(assoc r prop (prop e)) e])

;;; Basic entities

(defentity org)
;; Provided for compatibility purposes
(def ^:deprecated insert-customer insert-org)
(def ^:deprecated update-customer update-org)
(def ^:deprecated delete-customers delete-orgs)
(def ^:deprecated select-customers select-orgs)

(defentity repo)

(def creation-time->int (partial time->int :creation-time))
(def int->creation-time (partial int->time :creation-time))
(def last-inv-time->int (partial time->int :last-inv-time))
(def int->last-inv-time (partial int->time :last-inv-time))

(def prepare-webhook (comp int->creation-time int->last-inv-time))
(def convert-webhook (comp (partial copy-prop :creation-time)
                           (partial copy-prop :last-inv-time)))
(def convert-webhook-select (comp creation-time->int last-inv-time->int))

(defentity webhook
  {:before-insert prepare-webhook
   :after-insert  convert-webhook
   :before-update prepare-webhook
   :after-update  convert-webhook
   :after-select  convert-webhook-select})

(def prepare-user (partial keyword->str :type))
(def convert-user (partial copy-prop :type))
(def convert-user-select (partial str->keyword :type))

(defentity user
  {:before-insert prepare-user
   :after-insert  convert-user
   :before-update prepare-user
   :after-update  convert-user
   :after-select  convert-user-select})

(def git->edn (partial prop->edn :git))
(def edn->git (partial edn->prop :git))
(def git->build (partial copy-prop :git))

(def prepare-build (comp source->str git->edn prepare-timed))
(def convert-build (comp str->source git->build convert-timed))
(def convert-build-select (comp str->source edn->git convert-timed))

(defentity build
  {:before-insert prepare-build
   :after-insert  convert-build
   :before-update prepare-build
   :after-update  convert-build
   :after-select  convert-build-select})

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

;; For org params and ssh keys we don't store the labels in a separate table.
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

(defentity org-param label-filter-conversions)
(defentity ssh-key label-filter-conversions)

;;; Aggregate entities

(defaggregate repo-label)

(defn insert-repo-labels
  "Batch inserts multiple labels at once"
  [conn labels]
  (->> labels
       (map (juxt :repo-id :name :value))
       (insert-entities conn :repo-labels [:repo-id :name :value])))

(defaggregate user-org)

(defn insert-user-orgs
  "Batch inserts user/org links"
  [conn user-id org-ids]
  (when-not (empty? org-ids)
    (insert-entities conn :user-orgs
                     [:user-id :org-id]
                     (map (partial conj [user-id]) org-ids))))

(defaggregate org-param-value)

(defn insert-org-param-values
  "Batch inserts multiple parameter values at once"
  [conn values]
  (->> values
       (map (juxt :params-id :name :value))
       (insert-entities conn :org-param-values [:params-id :name :value])))

(defentity join-request)

(def prepare-email-reg (partial int->time :creation-time))
(def convert-email-reg (partial copy-prop :creation-time))
(def convert-email-reg-select (partial time->int :creation-time))

(defentity email-registration
  {:before-insert prepare-email-reg
   :after-insert  convert-email-reg
   :before-update prepare-email-reg
   :after-update  convert-email-reg
   :after-select  convert-email-reg-select})

(def prepare-credit-sub
  (comp (partial int->time :valid-from)
        (partial int->time :valid-until)))

(def convert-credit-sub
  (comp (partial copy-prop :valid-from)
        (partial copy-prop :valid-until)))

(def convert-credit-sub-select
  (comp (partial time->int :valid-from)
        (partial time->int :valid-until)))

(def credit-sub-conversions
  {:before-insert prepare-credit-sub
   :after-insert  convert-credit-sub
   :before-update prepare-credit-sub
   :after-update  convert-credit-sub
   :after-select  convert-credit-sub-select})

(defentity credit-subscription credit-sub-conversions)

(def prepare-credit
  (comp (partial int->time :valid-from)
        (partial int->time :valid-until)
        (partial keyword->str :type)))

(def convert-credit
  (comp (partial copy-prop :valid-from)
        (partial copy-prop :valid-until)
        (partial copy-prop :type)))

(def convert-credit-select
  (comp (partial time->int :valid-from)
        (partial time->int :valid-until)
        (partial str->keyword :type)))

(def cust-credit-conversions
  {:before-insert prepare-credit
   :after-insert  convert-credit
   :before-update prepare-credit
   :after-update  convert-credit
   :after-select  convert-credit-select})

(defentity org-credit cust-credit-conversions)

(def prepare-credit-cons (partial int->time :consumed-at))
(def convert-credit-cons (partial copy-prop :consumed-at))
(def convert-credit-cons-select (partial time->int :consumed-at))

(def credit-cons-conversions
  {:before-insert prepare-credit-cons
   :after-insert  convert-credit-cons
   :before-update prepare-credit-cons
   :after-update  convert-credit-cons
   :after-select  convert-credit-cons-select})

(defentity credit-consumption credit-cons-conversions)
(defentity bb-webhook)

(defaggregate crypto)

(defn update-crypto [conn crypto]
  (execute-update conn {:update :cryptos
                        :set crypto
                        :where [:= :org-id (:org-id crypto)]}))

(defaggregate sysadmin)

(def prepare-inv (comp (partial prop->edn :details)
                       (partial keyword->str :kind)
                       (partial int->time :date)))
(def convert-inv (comp (partial copy-prop :details)
                       (partial str->keyword :kind)
                       (partial time->int :date)))
(def convert-inv-select (comp (partial edn->prop :details)
                              (partial str->keyword :kind)
                              (partial time->int :date)))

(defentity invoice
  {:before-insert prepare-inv
   :after-insert  convert-inv
   :before-update prepare-inv
   :after-update  convert-inv
   :after-select  convert-inv-select})

;; Invoicing settings for orgs
(defn- split-lines [p]
  (fn [r]
    (mc/update-existing r p cs/split-lines)))

(defn- join-lines [p]
  (fn [r]
    (mc/update-existing r p (partial cs/join "\n"))))

(def prepare-org-inv (join-lines :address))
(def convert-org-inv (partial copy-prop :address))
(def convert-org-inv-select (split-lines :address))

(defaggregate org-invoicing
  {:id-col :org-id
   :before-insert prepare-org-inv
   :after-insert convert-org-inv
   :before-update prepare-org-inv
   :after-update convert-org-inv
   :after-select convert-org-inv-select})

(def prepare-runner-details (comp (partial prop->edn :details)
                                  (partial keyword->str :runner)))
(def convert-runner-details (comp (partial copy-prop :details)
                                  (partial str->keyword :runner)))
(def convert-runner-details-select (comp (partial edn->prop :details)
                                         (partial str->keyword :runner)))

(defaggregate build-runner-detail
  {:id-col :build-id
   :before-insert prepare-runner-details
   :after-insert  convert-runner-details
   :before-update prepare-runner-details
   :after-update  convert-runner-details
   :after-select  convert-runner-details-select})

(defaggregate repo-idx
  {:plural "repo-indices"
   :id-col :repo-id})

(def prepare-queued-task
  (comp (partial prop->edn :task)
        (partial int->time :creation-time)))
(def convert-queued-task
  (comp (partial copy-prop :task)
        (partial time->int :creation-time)))
(def convert-queued-task-select
  (comp (partial edn->prop :task)
        (partial time->int :creation-time)))

(defentity queued-task
  {:before-insert prepare-queued-task
   :after-insert  convert-queued-task
   :before-update prepare-queued-task
   :after-update  convert-queued-task
   :after-select  convert-queued-task-select})

(def prepare-job-evt
  (comp (partial prop->edn :details)
        (partial int->time :time)
        (partial keyword->str :event)))
(def convert-job-evt
  (comp (partial copy-prop :details)
        (partial time->int :time)
        (partial str->keyword :event)))
(def convert-job-evt-select
  (comp (partial edn->prop :details)
        (partial time->int :time)
        (partial str->keyword :event)))

(defaggregate job-event
  {:before-insert prepare-job-evt
   :after-insert  convert-job-evt
   :before-update prepare-job-evt
   :after-update  convert-job-evt
   :after-select  convert-job-evt-select})

(def prepare-token
  (partial int->time :valid-until))
(def convert-token
  (partial time->int :valid-until))

(defentity user-token
  {:before-insert prepare-token
   :after-insert  convert-token
   :before-update prepare-token
   :after-update  convert-token
   :after-select  convert-token})

(defentity org-token
  {:before-insert prepare-token
   :after-insert  convert-token
   :before-update prepare-token
   :after-update  convert-token
   :after-select  convert-token})

(def prepare-mailing
  (partial int->time :creation-time))
(def convert-mailing
  (partial time->int :creation-time))

(defentity mailing
  {:before-insert prepare-mailing
   :after-insert  convert-mailing
   :before-update prepare-mailing
   :after-update  convert-mailing
   :after-select  convert-mailing})

(def prepare-sent-mailing
  (comp (partial int->time :sent-at)
        (partial prop->edn :other-dests)))

(def convert-sent-mailing
  (comp (partial time->int :sent-at)
        (partial copy-prop :other-dests)))

(def convert-sent-mailing-select
  (comp (partial time->int :sent-at)
        (partial edn->prop :other-dests)))

(defentity sent-mailing
  {:before-insert prepare-sent-mailing
   :after-insert  convert-sent-mailing
   :before-update prepare-sent-mailing
   :after-update  convert-sent-mailing
   :after-select  convert-sent-mailing-select})

(defaggregate user-setting
  {:id-col :user-id})
