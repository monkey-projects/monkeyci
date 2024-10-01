(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold
             [bus :as mb]
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.ci
             [build :as build]
             [credits :as cr]
             [errors :as err]
             [extensions :as ext]
             [jobs :as j]
             [protocols :as p]
             [spec :as s]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runtime.script :as rs]
            [monkey.ci.spec.build :as sb]))

(defn- base-event
  "Creates a skeleton event with basic properties"
  [build type]
  (ec/make-event
   type 
   :src :script
   :sid (build/sid build)))

;; Wraps a job so it catches any exceptions.
(defrecord ErrorCatchingJob [target]
  j/Job
  (execute! [job ctx]
    (let [ctx-with-job (assoc ctx :job target)
          handle-error (fn [ex]
                         (log/error "Got job exception:" ex)
                         (let [u (err/unwrap-exception ex)]
                           (assoc bc/failure
                                  :exception u
                                  :message (ex-message u))))]
      (log/debug "Executing error catching job:" (bc/job-id target))
      (md/chain
       ;; Catch both sync and async errors
       (try 
         (-> (j/execute! target ctx-with-job)
             (md/catch handle-error))
         (catch Exception ex
           (handle-error ex)))
       (fn [r]
         (log/debug "Job" (j/job-id job) "ended with response:" r)
         r)))))

(defn- with-catch
  [job]
  (map->ErrorCatchingJob (-> (j/job->event job)
                             (assoc :target job))))

(def with-extensions
  "Wraps the job so any registered extensions get executed."
  ext/wrap-job)

(defn- pipeline-filter [pipeline]
  [[{:label "pipeline"
     :value pipeline}]])

(defn canceled-evt
  "Returns a deferred that will hold a `build/canceled` event, should it arrive.
   When deferred is realized, we unsubscribe from the bus."
  [bus]
  (let [src (mb/subscribe bus :build/canceled)]
    (-> (ms/take! src)
        (md/finally
          (fn []
            (ms/close! src))))))

(defn run-all-jobs
  "Executes all jobs in the set, in dependency order."
  [{:keys [pipeline events] :as rt} jobs]
  (let [pf (cond->> jobs
             ;; Filter jobs by pipeline, if given
             pipeline (j/filter-jobs (j/label-filter (pipeline-filter pipeline)))
             true (map (comp with-catch with-extensions)))
        ;; Cancel when build/canceled event received
        canceled? (atom false)
        evt-def (-> (canceled-evt (get-in rt [:event-bus :bus]))
                    (md/chain
                     (fn [_] (reset! canceled? true))))]
    (log/debug "Found" (count pf) "matching jobs:" (map bc/job-id pf))
    (try 
      (let [result @(j/execute-jobs! pf (assoc rt :canceled? canceled?))]
        (log/debug "Jobs executed, result is:" result)
        {:status (if (some (comp bc/failed? :result) (vals result)) :error :success)
         :jobs result})
      (finally
        ;; Realize the deferred so it cancels the subscription
        (when-not (md/realized? evt-def)
          (md/success! evt-def {}))))))

;;; Script loading

(defn- load-script
  "Loads the pipelines from the build script, by reading the script files 
   dynamically.  If the build script does not define its own namespace,
   one will be randomly generated to avoid collisions."
  [dir build-id]
  (let [tmp-ns (symbol (or build-id (str "build-" (random-uuid))))]
    ;; Declare a temporary namespace to load the file in, in case
    ;; it does not declare an ns of it's own.
    (in-ns tmp-ns)
    (clojure.core/use 'clojure.core)
    (try
      (let [path (io/file dir "build.clj")]
        (log/debug "Loading script:" path)
        ;; This should return pipelines to run
        (load-file (str path)))
      (finally
        ;; Return
        (in-ns 'monkey.ci.script)
        (remove-ns tmp-ns)))))

(defn- script-start-evt [rt jobs]
  (letfn [(mark-pending [job]
            (assoc job :status :pending))]
    (-> (base-event (:build rt) :script/start)
        (assoc :jobs (map (comp mark-pending j/job->event) jobs)))))

(defn- script-end-evts [rt _ res]
  ;; In addition to the script end event, we should also generate a job/skipped event
  ;; for each skipped job.
  (let [skipped (->> res
                     :jobs
                     vals
                     (filter (comp bc/skipped? :result))
                     (map (comp j/job-id :job)))]
    (->> [(-> (base-event (:build rt) :script/end)
              (assoc :status (:status res)))]
         (concat (mapv #(ec/make-event :job/skipped
                                       :sid (build/sid (:build rt))
                                       :job-id %)
                       skipped)))))

(defn script-init-evt [build script-dir]
  (-> (base-event build :script/initializing)
      (assoc :script-dir script-dir)))

(def run-all-jobs*
  (letfn [(error [rt _ ex]
            (log/error "Got error:" ex)
            (-> (base-event (:build rt) :script/end)
                (assoc :status :error
                       :exception (ex-message ex))))]
    (ec/wrapped run-all-jobs
                script-start-evt
                script-end-evts
                error)))

(defn- assign-ids
  "Assigns an id to each job that does not have one already."
  [jobs]
  (letfn [(assign-id [x id]
            (if (nil? (bc/job-id x))
              (assoc x :id id)
              x))]
    ;; TODO Sanitize existing ids
    (map-indexed (fn [i job]
                   (assign-id job (format "job-%d" (inc i))))
                 jobs)))

(defn resolve-jobs
  "The build script either returns a list of pipelines, a set of jobs or a function 
   that returns either.  This function resolves the jobs by processing the script
   return value."
  [p rt]
  (-> (j/resolve-jobs p rt)
      (assign-ids)))

(defn load-jobs
  "Loads the script and resolves the jobs"
  [build rt]
  (-> (load-script (build/script-dir build) (build/build-id build))
      (resolve-jobs rt)))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is executed in 
   this same process."
  [rt]
  (let [build (rs/build rt)
        build-id (build/build-id build)
        script-dir (build/script-dir build)]
    #_(s/valid? ::sb/build build)
    (log/debug "Executing script for build" build-id "at:" script-dir)
    (log/debug "Build map:" build)
    (try
      (let [jobs (load-jobs build (j/rt->context rt))]
        (log/trace "Jobs:" jobs)
        (log/debug "Loaded" (count jobs) "jobs:" (map bc/job-id jobs))
        (run-all-jobs* rt jobs))
      (catch Exception ex
        (log/error "Unable to load build script" ex)
        (let [msg ((some-fn (comp ex-message ex-cause)
                            ex-message) ex)]
          (ec/post-events (:events rt)
                          [(-> (base-event build :script/end)
                               (assoc :script (build/script build)
                                      :message msg))])
          (assoc bc/failure
                 :message msg
                 :exception ex))))))
