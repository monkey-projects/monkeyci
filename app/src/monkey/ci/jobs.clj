(ns monkey.ci.jobs
  "Handles job execution and ordering in a build"
  (:require [clojure.walk :as cw]
            [medley.core :as mc]))

(def deps "Get job dependencies" :dependencies)
(def status "Get job status" :status)

(def job? "Checks if object is a job" map?)

(def pending? (comp (some-fn nil? (partial = :pending)) status))
(def running? (comp (partial = :running) status))
(def failed?  (comp (partial = :failure) status))
(def success? (comp (partial = :success) status))

(defn job
  "Creates a new job"
  ([id opts]
   (merge opts {:id id}))
  ([id]
   (job id {})))

(defn- find-dependents
  "Finds all jobs that are dependent on this job"
  [job others]
  (letfn [(dependent? [j]
            (and (some? (deps j))
                 ((deps j) job)))]
    (filterv dependent? others)))

#_(defn make-job-graph
  "Given a set of jobs, creates a graph where jobs are sorted according
   to dependencies.  The roots are all jobs that don't have dependencies,
   and below that there are those that are dependent on the parents."
  [jobs]
  (let [jobs (map #(mc/update-existing % :dependencies set) jobs)
        roots (filterv (comp empty? deps) jobs)
        add-dependents (fn [x]
                         (if (job? x)
                           (let [d (find-dependents x jobs)]
                             (cond-> [x]
                               (not-empty d) (conj d)))
                           x))]
    (cw/prewalk add-dependents roots)))

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
