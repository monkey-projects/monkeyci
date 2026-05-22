(ns monkey.ci.script.jobs
  (:require [monkey.ci.script.utils :as u]))

(def job-id :id)

(def job-type :type)

(def job-types
  "Known job types"
  #{:action :container})

(defn job?
  "Checks if object is a job"
  [x]
  ;; Can't use def with partial here, for some reason the compiler always says false.
  ;; Perhaps because partial does a closure on declaration.
  #_(satisfies? Job x)
  (and (map? x) (job-types (:type x))))

(defn action-job
  "Creates a new job"
  ([id action opts]
   (merge opts {:id id :action action :type :action}))
  ([id action]
   (action-job id action {})))

(def action-job? (comp (partial = :action) job-type))

(defn container-job
  "Creates a job that executes in a container"
  [id props]
  (assoc props
         :type :container
         :id id))

(def container-job? (comp (partial = :container) job-type))

(defprotocol JobResolvable
  "Able to resolve into jobs (zero or more)"
  (resolve-jobs [x ctx]))

(defn job-fn? [x]
  (true? (:job (meta x))))

(defn- fn->action-job [f]
  (action-job (or (job-id f)
                  ;; FIXME This does not work for anonymous functions
                  (u/fn-name f))
              f))

(defn- resolve-sequential [v ctx]
  (mapcat #(resolve-jobs % ctx) v))

(extend-protocol JobResolvable
  clojure.lang.Fn
  (resolve-jobs [f rt]
    ;; Recursively resolve job, unless this is a job fn in itself
    (if (job-fn? f)
      [(fn->action-job f)]
      (resolve-jobs (f rt) rt)))

  clojure.lang.Var
  (resolve-jobs [v rt]
    (resolve-jobs (var-get v) rt))

  nil
  (resolve-jobs [_ _]
    [])

  clojure.lang.PersistentVector
  (resolve-jobs [v rt]
    (resolve-sequential v rt))

  clojure.lang.PersistentArrayMap
  (resolve-jobs [m rt]
    (when (job? m) [m]))

  clojure.lang.PersistentHashMap
  (resolve-jobs [m rt]
    (when (job? m) [m]))

  clojure.lang.LazySeq
  (resolve-jobs [v rt]
    (resolve-sequential v rt)))
