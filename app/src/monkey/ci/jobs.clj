(ns monkey.ci.jobs
  "Handles job execution and ordering in a build"
  (:require [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.protocols :as p]))

(def deps "Get job dependencies" :dependencies)
(def status "Get job status" :status)
(def labels "Get job labels" :labels)

(defprotocol Job
  "Base job protocol that is able to execute it, taking the runtime as argument."
  (execute! [job rt]))

(def job? "Checks if object is a job" (partial satisfies? Job))

(def pending? (comp (some-fn nil? (partial = :pending)) status))
(def running? (comp (partial = :running) status))
(def failed?  (comp (partial = :failure) status))
(def success? (comp (partial = :success) status))

;; Job that executes a function
(defrecord ActionJob [id status action opts]
  Job
  (execute! [_ rt]
    (action rt)))

(defn action-job
  "Creates a new job"
  ([id action opts]
   (map->ActionJob (merge opts {:id id :action action})))
  ([id action]
   (action-job id action {})))

(defn- find-dependents
  "Finds all jobs that are dependent on this job"
  [job others]
  (letfn [(dependent? [j]
            (and (some? (deps j))
                 ((deps j) job)))]
    (filterv dependent? others)))

(defn- find-job
  "Find job by id"
  [jobs id]
  (->> jobs
       (filter (comp (partial = id) :id))
       (first)))

(defn fulfilled?
  "True if all this job's dependencies have been fulfilled (i.e. they are
   successful)."
  [others job]
  (letfn [(all-ok [])])
  (->> (deps job)
       (map (partial find-job others))
       (every? success?)))

(defn next-jobs
  "Returns a list of next jobs that are eligible for execution.  If all jobs are
   pending, returns the starting jobs, those that don't have any dependencies.  
   Otherwise returns all pending jobs that have their dependencies fulfilled."
  [jobs]
  (->> jobs
       (filter pending?)
       (filter (partial fulfilled? jobs))))

(extend-protocol p/JobResolvable
  ActionJob
  (resolve-job [job _]
    job)
  
  clojure.lang.IFn
  (resolve-job [f rt]
    ;; Recursively resolve job
    (p/resolve-job (f rt) rt))

  clojure.lang.Var
  (resolve-job [v rt]
    (p/resolve-job (var-get v) rt))

  nil
  (resolve-job [_ _]
    nil))

(def resolve-job p/resolve-job)

(defn execute-jobs!
  "Executes all jobs in dependency order.  Returns a deferred that will hold
   the results of all executed jobs."
  [jobs rt]
  (md/success-deferred nil))
