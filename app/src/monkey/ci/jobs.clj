(ns monkey.ci.jobs
  "Handles job execution and ordering in a build"
  (:require [clojure.tools.logging :as log]
            [clojure.set :as cs]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as co]
             [labels :as lbl]
             [protocols :as p]
             [utils :as u]]))

(def deps "Get job dependencies" :dependencies)
(def status "Get job status" :status)
(def labels "Get job labels" :labels)
(def job-id "Gets job id" :id)

(defprotocol Job
  "Base job protocol that is able to execute it, taking the runtime as argument."
  (execute! [job rt]))

(def job? "Checks if object is a job" (partial satisfies? Job))

(def pending? (comp (some-fn nil? (partial = :pending)) status))
(def running? (comp (partial = :running) status))
(def failed?  (comp (partial = :failure) status))
(def success? (comp (partial = :success) status))

(extend-protocol Job
  monkey.ci.build.core.ActionJob
  (execute! [{:keys [action]} rt]
    ;; TODO Rewrite work dir
    ;; TODO Caches and artifacts
    (action rt))

  monkey.ci.build.core.ContainerJob
  (execute! [this rt]
    (co/run-container (assoc rt :job this))))

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
       (filter (comp (partial = id) job-id))
       (first)))

(defn- fulfilled?
  "True if all this job's dependencies have been fulfilled (i.e. they are
   successful)."
  [others job]
  (->> (deps job)
       (map (partial (comp others job-id) others))
       (every? success?)))

(defn- next-jobs*
  "Retrieves next jobs eligible for execution, using a map of `{job-id job}`
   for performance reasons."
  [jobs-by-id]
  (mc/filter-vals (every-pred pending?
                              (partial fulfilled? jobs-by-id))
                  jobs-by-id))

(defn- group-by-id [jobs]
  (->> jobs
       (group-by :id)
       (mc/map-vals first)))

(defn next-jobs
  "Returns a list of next jobs that are eligible for execution.  If all jobs are
   pending, returns the starting jobs, those that don't have any dependencies.  
   Otherwise returns all pending jobs that have their dependencies fulfilled."
  [jobs]
  (->> jobs
       (group-by-id)
       (next-jobs*)
       (vals)))

(defn job-fn? [x]
  (true? (:job (meta x))))

(defn- fn->action-job [f]
  (bc/action-job (or (bc/job-id f)
                     (u/fn-name f))
                 f))

(extend-protocol p/JobResolvable
  monkey.ci.build.core.ActionJob
  (resolve-job [job _]
    job)

  monkey.ci.build.core.ContainerJob
  (resolve-job [job _]
    job)
  
  clojure.lang.IFn
  (resolve-job [f rt]
    ;; Recursively resolve job, unless this is a job fn in itself
    (if (job-fn? f)
      (fn->action-job f)
      (p/resolve-job (f rt) rt)))

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
  (let [grouped (group-by-id jobs)
        
        execute-all!
        (fn execute-all [jobs state]
          ;; Execute all jobs in parallel, return a list of deferreds
          (log/info "Starting" (count jobs) "pending jobs:" (map job-id jobs))
          (map (fn [j]
                 (md/chain
                  (execute! j (assoc-in rt [:build :jobs] state))
                  (partial vector j)))
               jobs))

        add-to-results
        (fn [global [job r]]
          (assoc global
                 (job-id job)
                 {:job job
                  :result r}))

        update-job-state
        (fn [jobs job s]
          (assoc-in jobs [(job-id job) :status] s))

        result->status
        (fn [r]
          (if (bc/success? r)
            :success
            :failure))]
    ;; Sets up a loop that checks if any jobs are pending for execution, and
    ;; starts them in parallel.  Then adds them to any already executing jobs.
    ;; It then waits for the first job to finish, and adds its result to the
    ;; global result map.  Then performs the next iteration with any new pending
    ;; jobs.  Stops when no more jobs are eligible for execution and all running
    ;; jobs have finished.
    (md/loop [state grouped
              executing []
              results {}]
      (let [n (next-jobs* state)]
        (log/trace "Job state:" state)
        (log/debugf "There are %d pending jobs: %s" (count n) (keys n))
        (log/debugf "There are %d jobs currently executing: %s"
                    (count executing) (mapv (comp job-id first) executing))
        (if (and (empty? n) (empty? executing))
          ;; Done, no more jobs to run and all running jobs have terminated
          results
          ;; More jobs to run, or at least one job is still executing
          (md/chain
           (->> (execute-all! (vals n) state)
                (concat executing)
                ;; Wait for next running job to terminate
                (apply md/alt))
           (fn [[job res :as out]]
             (log/info "Job finished:" (job-id job))
             (md/recur
              (update-job-state state job (result->status res))
              (remove (partial = out) executing)
              (add-to-results results out)))))))))

(defn filter-jobs
  "Applies a filter to the given jobs, but includes all dependencies of jobs that
   match the filter, even though the dependencies themselves may not match it."
  [pred jobs]
  (let [g (group-by-id jobs)
        add-missing-deps (fn [r]
                           (let [all (->> (vals r)
                                          (mapcat :dependencies)
                                          (set))
                                 missing (cs/difference all (set (:keys r)))]
                             (merge r (select-keys g missing))))]
    (loop [p {}
           r (group-by-id (filter pred jobs))]
      (if (= p r)
        (vals r)
        (recur r (add-missing-deps r))))))

(defn label-filter
  "Predicate function that matches a job by its labels"
  [f]
  (fn [{:keys [labels]}]
    (lbl/matches-labels? f labels)))

(def resolvable? (partial satisfies? p/JobResolvable))

(defn resolve-all
  "Resolves all jobs, removes anything that's not resolvable or not a job."
  [rt jobs]
  (->> jobs
       (filter resolvable?)
       (map #(p/resolve-job % rt))
       (filter job?)))
