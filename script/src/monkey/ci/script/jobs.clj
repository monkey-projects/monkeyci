(ns monkey.ci.script.jobs
  (:require [clojure.core.async :as ca]
            [clojure.set :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.builders :as eb]
            [monkey.ci.script
             [build :as b]
             [utils :as u]]
            [monkey.ci.time :as t]
            [monkey.ci.utils.path :as up]
            [monkey.mailman.core :as mmc]))

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

(defn resolvable? [x]
  (satisfies? JobResolvable x))

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

(defn filter-jobs
  "Applies a filter to the given jobs, but includes all dependencies of jobs that
   match the filter, even though the dependencies themselves may not match it."
  [pred jobs]
  (let [g (mc/index-by job-id jobs)
        add-missing-deps (fn [r]
                           (let [all (->> (vals r)
                                          (mapcat :dependencies)
                                          (set))
                                 missing (cs/difference all (set (:keys r)))]
                             (merge r (select-keys g missing))))]
    (loop [p {}
           r (mc/index-by job-id (filter pred jobs))]
      (if (= p r)
        (vals r)
        (recur r (add-missing-deps r))))))

(defn- make-job-dir-absolute
  "Rewrites the job dir in the context so it becomes an absolute path, calculated
   relative to the checkout dir."
  [{:keys [job build] :as rt}]
  (let [checkout-dir (b/checkout-dir build)]
    (update-in rt [:job :work-dir]
               (fn [d]
                 (if d
                   (up/abs-path checkout-dir d)
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
  [{:keys [action] :as job} on-error]
  (fn [rt]
    (letfn [(assign-id [j]
              (cond-> j
                (nil? (job-id j)) (assoc :id (job-id job))))]
      (let [writer (java.io.StringWriter.)]
        (ca/go
         ;; Ensure this executes async
          (let [r (ca/<! (ca/thread
                           (binding [*out* writer] ; Capture output
                             (try
                               (action (rt->context rt)) ; Only pass necessary info
                               (catch Exception ex
                                 (on-error ex))))))] 
            (log/debug "Action result:" r)
            (cond
              ;; `nil` is treated as a success
              (nil? r) (add-output b/success writer)
              ;; Valid response
              (b/status? r) (add-output r writer)
              ;; XXX This is not really supported by the user interface right now, but it
              ;; would be quite powerful.
              (resolvable? r) (when-let [child (some-> (resolve-jobs r rt)
                                                       first
                                                       (assign-id))]
                                (ca/<! (execute! child (assoc rt :job child))))
              ;; Treat any other result as a success, but add a warning
              :else (-> b/success
                        (b/add-warning {:message "Invalid action result"
                                        :result r})
                        (add-output writer)))))))))

(defn- post-error [broker job sid ex]
  (mmc/post-events broker
                   [(eb/job-end-evt (job-id job) sid (-> bc/failure
                                                         (bc/with-message (ex-message ex))))]))

(defn execute!
  "Executes the given action job.  Posts start/end events to the broker provided in the
   context.  Returns a channel that will hold the result."
  [job {:keys [mailman] :as ctx}]
  (let [build (:build ctx)
        build-sid (b/sid build)
        a (-> (recurse-action job (partial post-error mailman job build-sid))
              ;; TODO Caches and artifacts
              #_(cache/wrap-caches)
              #_(art/wrap-artifacts))
        job (assoc job
                   :start-time (t/now))]
    (mmc/post-events mailman
                     [(-> (eb/job-start-evt (job-id job) build-sid)
                          ;; For action jobs, add the credit multiplier on job start since there is
                          ;; no `initializing` event.
                          (merge (select-keys build [:credit-multiplier])))])
    (ca/go
      (let [r (ca/<! (-> ctx
                         (make-job-dir-absolute)
                         (a)))]
        (log/debug "Action job" (job-id job) "executed with result:" r)
        (mmc/post-events mailman [(eb/job-executed-evt (job-id job) build-sid r)])
        r))))
