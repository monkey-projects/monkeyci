(ns monkey.ci.jobs
  "Handles job execution and ordering in a build"
  (:require [clojure.set :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [artifacts :as art]
             [build :as build]
             [cache :as cache]
             [labels :as lbl]
             [protocols :as p]
             [time :as t]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events
             [builders :as eb]
             [core :as ec]
             [mailman :as em]]))

(def deps "Get job dependencies" :dependencies)
(def status "Get job status" :status)
(def labels "Get job labels" :labels)
(def save-artifacts "Gets artifacts saved by job" :save-artifacts)
(def job-id "Gets job id" :id)
(def work-dir "Gets job work dir" :work-dir)

(def max-job-timeout (* 20 60 1000))

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

(defn resolvable? [x]
  (satisfies? p/JobResolvable x))

(def pending? (comp (some-fn nil? (partial = :pending)) status))
(def queued?  (comp (partial = :queued) status))
(def running? (comp (partial = :running) status))
(def failed?  (comp #{:error :failure} status))
(def success? (comp (partial = :success) status))
(def active?  (comp #{:queued :initializing :running} status))

(def as-serializable eb/job->event)
(def job->event eb/job->event)

(def job-status-evt eb/job-status-evt)
(def job-pending-evt eb/job-pending-evt)
(def job-queued-evt eb/job-queued-evt)
(def job-skipped-evt eb/job-skipped-evt)
(def job-initializing-evt eb/job-initializing-evt)
(def job-start-evt eb/job-start-evt)
(def job-executed-evt eb/job-executed-evt)
(def job-end-evt eb/job-end-evt)

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
  ;; TODO Move all these into a "components" key so we can remove them all at once
  (dissoc rt :events :containers :artifacts :cache :mailman))

(defn- add-output [r writer]
  ;; Add output to the result
  (.flush writer)
  (let [out (.toString writer)]
    (log/trace "Output from job:" out)
    (cond-> r
      (not-empty out) (assoc :output out))))

(declare execute!)

(defn- recurse-action
  "An action may return another job definition, especially in legacy builds.
   This function checks the result, and if it's not a regular response, it
   tries to construct a new job from it and execute it recursively."
  [{:keys [action] :as job}]
  (fn [rt]
    (letfn [(assign-id [j]
              (cond-> j
                (nil? (bc/job-id j)) (assoc :id (bc/job-id job))))]
      (let [writer (java.io.StringWriter.)]
        (md/chain
         ;; Ensure this executes async by wrapping it in a future
         (md/future
           (binding [*out* writer]       ; Capture output
             (action (rt->context rt)))) ; Only pass necessary info
         (fn [r]
           (log/debug "Action result:" r)
           (cond
             ;; `nil` is treated as a success
             (nil? r) (add-output bc/success writer)
             ;; Valid response
             (bc/status? r) (add-output r writer)
             ;; XXX This is not really supported by the user interface right now, but it
             ;; would be quite powerful.
             (resolvable? r) (when-let [child (some-> (p/resolve-jobs r rt)
                                                      first
                                                      (assign-id))]
                               (execute! child (assoc rt :job child)))
             ;; Treat any other result as a success, but add a warning
             :else (-> bc/success
                       (bc/add-warning {:message "Invalid action result"
                                        :result r})
                       (add-output writer)))))))))

(defn execute!
  "Executes the given action job"
  [job ctx]
  (let [build (:build ctx)
        build-sid (build/sid build)
        a (-> (recurse-action job)
              (cache/wrap-caches)
              (art/wrap-artifacts))
        job (assoc job
                   :start-time (t/now))]
    (em/post-events (:mailman ctx)
                    [(-> (job-start-evt (job-id job) build-sid)
                         ;; For action jobs, add the credit multiplier on job start since there is
                         ;; no `initializing` event.
                         (merge (select-keys build [:credit-multiplier])))])
    (-> ctx
        (make-job-dir-absolute)
        (a)
        (md/chain
         (fn [r]
           (log/debug "Action job" (job-id job) "executed with result:" r)
           (md/chain
            (em/post-events (:mailman ctx) [(job-executed-evt (job-id job) build-sid r)])
            (constantly r)))))))

(defn find-dependents
  "Finds all jobs that are dependent on this job"
  [job others]
  (letfn [(dependent? [j]
            (and (some? (deps j))
                 ((set (deps j)) job)))]
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
  clojure.lang.Fn
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
    (when (job? m) [m]))

  clojure.lang.PersistentHashMap
  (resolve-jobs [m rt]
    (when (job? m) [m]))

  clojure.lang.LazySeq
  (resolve-jobs [v rt]
    (resolve-sequential v rt)))

(def resolve-jobs p/resolve-jobs)

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

;; Alternative algorithm
#_(defn filter-jobs
    "Applies filter fn to the given list of jobs, but also includes any dependencies."
    [f jobs]
    (let [by-id (->> (group-by j/job-id jobs)
                     (mc/map-vals first))]
      (loop [r {}
             ;; Use set for deduplication
             todo (set (filter f jobs))]
        (if (empty? todo)
          (vals r)
          (let [n (first todo)]
            (recur (assoc r (j/job-id n) n)
                   (-> (rest todo)
                       (concat (map by-id (j/deps n)))
                       (set))))))))

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

(defn set-credit-multiplier [job cm]
  (assoc job :credit-multiplier cm))

(defn size->cpus [job]
  (or (:size job) (:cpus job) 1))

(defn size->mem [job]
  (or (some-> (:size job) (* 2))
      (:memory job)
      2))
