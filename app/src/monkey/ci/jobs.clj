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
             [artifacts :as art]
             [build :as build]
             [cache :as cache]
             [containers :as co]
             [credits :as cr]
             [labels :as lbl]
             [protocols :as p]
             [time :as t]
             [utils :as u]]
            [monkey.ci.events.core :as ec]))

(def deps "Get job dependencies" :dependencies)
(def status "Get job status" :status)
(def labels "Get job labels" :labels)
(def save-artifacts "Gets artifacts saved by job" :save-artifacts)
(def job-id "Gets job id" :id)
(def work-dir "Gets job work dir" :work-dir)

(def max-job-timeout (* 20 60 1000))

(defprotocol Job
  "Base job protocol that is able to execute it, taking the runtime as argument."
  (execute! [job rt]))

(defn job?
  "Checks if object is a job"
  [x]
  ;; Can't use def with partial here, for some reason the compiler always says false.
  ;; Perhaps because partial does a closure on declaration.
  (satisfies? Job x))

(defn resolvable? [x]
  (satisfies? p/JobResolvable x))

(def pending? (comp (some-fn nil? (partial = :pending)) status))
(def running? (comp (partial = :running) status))
(def failed?  (comp (partial = :failure) status))
(def success? (comp (partial = :success) status))

(defn as-serializable
  "Converts job into something that can be converted to edn"
  [job]
  (letfn [(art->ser [a]
            (select-keys a [:id :path]))]
    (-> job
        (select-keys (concat [:status :start-time :end-time deps labels :extensions :credit-multiplier :script
                              :memory :cpus :arch :work-dir
                              save-artifacts :restore-artifacts :caches]
                             co/props))
        (mc/update-existing :save-artifacts (partial map art->ser))
        (mc/update-existing :restore-artifacts (partial map art->ser))
        (mc/update-existing :caches (partial map art->ser))
        (assoc :id (bc/job-id job)))))

(def job->event
  "Converts job into something that can be put in an event"
  as-serializable)

(defn- base-event
  "Creates a skeleton event with basic properties"
  [type job-id build-sid]
  (ec/make-event 
   type
   :src :script
   :sid build-sid
   :job-id job-id))

(defn job-initializing-evt [job-id build-sid cm]
  (-> (base-event :job/initializing job-id build-sid)
      (assoc :credit-multiplier cm)))

(def job-start-evt (partial base-event :job/start))

(defn- job-status-evt [type job-id build-sid {:keys [status] :as r}]
  (let [r (dissoc r :status :exception)]
    (-> (base-event type job-id build-sid)
        (assoc :status status
               :result r))))

(def job-executed-evt
  "Creates an event that indicates the job has executed, but has not been completed yet.
   Extensions may need to be applied first."
  (partial job-status-evt :job/executed))

(def job-end-evt
  "Event that indicates the job has been fully completed.  The result should not change anymore."
  (partial job-status-evt :job/end))

(defn ex->result
  "Creates result structure from an exception"
  [ex]
  (if (instance? java.lang.Exception ex)
    (ec/exception-result ex)
    ex))

(defn- make-job-dir-absolute
  "Rewrites the job dir in the context so it becomes an absolute path, calculated
   relative to the checkout dir."
  [{:keys [job build] :as rt}]
  (let [checkout-dir (build/checkout-dir build)]
    (update-in rt [:job :work-dir]
               (fn [d]
                 (if d
                   (u/abs-path checkout-dir d)
                   checkout-dir)))))

(defn rt->context [rt]
  (dissoc rt :events :containers :artifacts :cache))

(defn- recurse-action
  "An action may return another job definition, especially in legacy builds.
   This function checks the result, and if it's not a regular response, it
   tries to construct a new job from it and execute it recursively."
  [{:keys [action] :as job}]
  (fn [rt]
    (let [assign-id (fn [j]
                      (cond-> j
                        (nil? (bc/job-id j)) (assoc :id (bc/job-id job))))]
      (md/chain
       ;; Ensure this executes async by wrapping it in a future
       (md/future
         (action (rt->context rt)))        ; Only pass necessary info
       (fn [r]
         (cond
           ;; Valid response
           (or (nil? r) (bc/status? r)) r
           (resolvable? r) (when-let [child (some-> (p/resolve-jobs r rt)
                                                    first
                                                    (assign-id))]
                             (execute! child (assoc rt :job child)))))))))

(extend-protocol Job
  monkey.ci.build.core.ActionJob
  (execute! [job ctx]
    (let [build (:build ctx)
          build-sid (build/sid build)
          a (-> (recurse-action job)
                (cache/wrap-caches)
                (art/wrap-artifacts))
          job (assoc job
                     :start-time (t/now))]
      (ec/post-events (:events ctx)
                      [(-> (job-start-evt (job-id job) build-sid)
                           ;; For action jobs, add the credit multiplier on job start since there is
                           ;; no `initializing` event.
                           (merge (select-keys build [:credit-multiplier])))])
      (-> ctx
          (make-job-dir-absolute)
          (a)
          (md/chain 
           #(or % bc/success)
           (fn [r]
             (md/chain
              (ec/post-events (:events ctx) [(job-executed-evt (job-id job) build-sid r)])
              (constantly r)))))))

  monkey.ci.build.core.ContainerJob
  (execute! [this ctx]
    (md/chain
     (p/run-container (:containers ctx) this)
     (fn [r]
       (log/debug "Container job finished with result:" r)
       ;; Don't add the full result otherwise it will be sent out as an event
       (-> (if (= 0 (:exit r))
             bc/success
             bc/failure)
           (merge (select-keys r [:exit :message])))))))

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
                     ;; FIXME This does not work for anonymous functions
                     (u/fn-name f))
                 f))

(defn- resolve-sequential [v rt]
  (mapcat #(p/resolve-jobs % rt) v))

(defn- add-dependencies
  "Given a sequence of jobs from a pipeline, makes each job dependent on the previous one."
  [jobs]
  (reduce (fn [r j]
            (conj r (cond-> j
                      (not-empty r)
                      (update :dependencies (comp vec distinct conj) (:id (last r))))))
          []
          jobs))

(defn- add-pipeline-name-lbl
  "When jobs are resolved from a pipeline, adds the pipeline name as a label"
  [jobs {:keys [name]}]
  (cond->> jobs
    name (map #(assoc-in % [labels "pipeline"] name))))

(extend-protocol p/JobResolvable
  monkey.ci.build.core.ActionJob
  (resolve-jobs [job _]
    [job])

  monkey.ci.build.core.ContainerJob
  (resolve-jobs [job _]
    [job])
  
  clojure.lang.IFn
  (resolve-jobs [f rt]
    ;; Recursively resolve job, unless this is a job fn in itself
    (if (job-fn? f)
      [(fn->action-job f)]
      (p/resolve-jobs (f rt) rt)))

  clojure.lang.Var
  (resolve-jobs [v rt]
    (p/resolve-jobs (var-get v) rt))

  nil
  (resolve-jobs [_ _]
    [])

  clojure.lang.PersistentVector
  (resolve-jobs [v rt]
    (resolve-sequential v rt))

  clojure.lang.PersistentArrayMap
  (resolve-jobs [m rt]
    ;; Legacy step, as a result of a function
    (p/resolve-jobs (bc/step->job m) rt))

  clojure.lang.LazySeq
  (resolve-jobs [v rt]
    (resolve-sequential v rt))

  monkey.ci.build.core.Pipeline
  (resolve-jobs [p rt]
    (-> (:jobs p)
        (p/resolve-jobs rt)
        (add-dependencies)
        (add-pipeline-name-lbl p))))

(def resolve-jobs p/resolve-jobs)

(defn execute-next
  "Performs the next iteration of job execution"
  [state executing results rt]
  (letfn [(add-to-results [global [job r]]
            (assoc global
                   (job-id job)
                   {:job job
                    :result r}))

          (mark-pending-skipped [state res]
            (let [pending (clojure.set/difference (set (keys state)) (set (keys res)))]
              (reduce (fn [r id]
                        (add-to-results r [(get state id) bc/skipped]))
                      res
                      pending)))

          (update-job-state [state job s]
            (assoc-in state [(job-id job) :status] s))
          
          (update-multiple-jobs [state jobs js]
            (reduce (fn [res j]
                      (update-job-state res j js))
                    state
                    jobs))

          (post-end [job res]
            (md/chain
             (ec/post-events (:events rt)
                             (job-end-evt (job-id job)
                                          (build/sid (:build rt))
                                          res))
             (constantly res)))

          (execute-all! [jobs state]
            ;; Execute all jobs in parallel, return a map of job-ids and deferreds
            (log/info "Starting" (count jobs) "pending jobs:" (map job-id jobs))
            (reduce (fn [r j]
                      (assoc r
                             (job-id j)
                             ;; Note that this must execute async, otherwise jobs will not be
                             ;; run concurrently.
                             (-> (execute! j (assoc-in rt [:build :jobs] state))
                                 (md/chain
                                  (partial post-end j)
                                  (partial vector j))
                                 (md/finally
                                   (fn []
                                     (log/debug "Jobs completed and job/end events (probably) sent for"
                                                (map job-id jobs)))))))
                    {}
                    jobs))

          (result->status [r]
            (if (bc/success? r)
              :success
              :failure))

          (canceled? []
            (some-> rt :canceled? deref))]
    
    (let [c? (canceled?)
          n (if c? {} (next-jobs* state))]
      ;; TODO Do not start new jobs if build canceled
      (log/trace "Job state:" state)
      (log/debugf "There are %d pending jobs: %s" (count n) (keys n))
      (log/debugf "There are %d jobs currently executing: %s" (count executing) (keys executing))
      (if (and (empty? n) (empty? executing))
        ;; Done, no more jobs to run and all running jobs have terminated.
        ;; Mark any jobs that have not been executed as skipped.
        (mark-pending-skipped state results)
        ;; More jobs to run, or at least one job is still executing
        ;; TODO Set a limit on the number of jobs to execute at the same time
        (md/chain
         (md/let-flow [to-execute (vals n)
                       updated-state (update-multiple-jobs state to-execute :running)
                       all-executing (if c?
                                       ;; When canceled, just continue executing the running jobs
                                       executing
                                       (->> (execute-all! to-execute state)
                                            (merge executing)))
                       ;; Wait for next running job to terminate
                       next-done (apply md/alt (vals all-executing))]
           [updated-state next-done all-executing])
         (fn [[state [job out :as d] all]]
           (log/info "Job finished:" (job-id job))
           [(update-job-state state job (result->status out))
            (dissoc all (job-id job))
            (add-to-results results d)]))))))

(defn execute-jobs!
  "Executes all jobs in dependency order.  Returns a deferred that will hold
   the results of all executed jobs.  The runtime can contain an atom `canceled?` 
   that should hold `true` if the jobs loop should stop executing."
  [jobs rt]
  ;; Sets up a loop that checks if any jobs are pending for execution, and
  ;; starts them in parallel.  Then adds them to any already executing jobs.
  ;; It then waits for the first job to finish, and adds its result to the
  ;; global result map.  Then performs the next iteration with any new pending
  ;; jobs.  Stops when no more jobs are eligible for execution and all running
  ;; jobs have finished.
  (md/loop [state (group-by-id jobs)
            executing {}
            results {}]
    (let [next (execute-next state executing results rt)]
      (if (map? next)
        ;; We're done
        next
        ;; Not done, do next iteration
        (md/chain next (partial apply md/recur))))))

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

(defn resolve-all
  "Resolves all jobs, removes anything that's not resolvable or not a job."
  [rt jobs]
  (->> jobs
       (filter resolvable?)
       (mapcat #(resolve-jobs % rt))
       (filter job?)))

(extend-protocol cr/CreditConsumer
  monkey.ci.build.core.ActionJob
  (credit-multiplier [job rt]
    ((cr/runner-credit-consumer-fn rt) job))

  monkey.ci.build.core.ContainerJob
  (credit-multiplier [job rt]
    ((cr/container-credit-consumer-fn rt) job)))

(defn set-credit-multiplier [job cm]
  (assoc job :credit-multiplier cm))
