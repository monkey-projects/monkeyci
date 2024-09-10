(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as build]
             [credits :as cr]
             [extensions :as ext]
             [jobs :as j]
             [protocols :as p]
             [spec :as s]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runtime.script :as rs]
            [monkey.ci.spec.build :as sb]))

(defn rt->context [rt]
  (select-keys rt [:build :api]))

(defn- wrapped
  "Sets the event poster in the runtime."
  [f before after]
  (let [error (fn [& args]
                ;; On error, add the exception to the result of the 'after' event
                (let [ex (last args)]
                  (log/error "Got error:" ex)
                  (assoc (apply after (concat (butlast args) [{}]))
                         :exception (.getMessage ex))))
        w (ec/wrapped f before after error)]
    (fn [rt & more]
      (apply w rt more))))

(defn- base-event
  "Creates a skeleton event with basic properties"
  [rt type]
  {:type type
   :src :script
   :sid (build/get-sid rt)
   :time (u/now)})

(defn- job-start-evt [{:keys [job] :as rt}]
  (-> (base-event rt :job/start)
      (assoc :job (j/job->event job)
             :message "Job started")))

(defn- job-end-evt [{:keys [job] :as rt} {:keys [status message exception] :as r}]
  (let [r (dissoc r :status :exception)]
    (-> (base-event rt :job/end)
        (assoc :message "Job completed"
               :job (cond-> (j/job->event job)
                      true (assoc :status status)
                      ;; Add any extra information to the result key
                      (not-empty r) (assoc :result r)
                      (some? exception) (assoc :message (or message (.getMessage exception))
                                               :stack-trace (u/stack-trace exception)))))))

;; Wraps a job so it fires an event before and after execution, and also
;; catches any exceptions.
(defrecord EventFiringJob [events target]
  j/Job
  (execute! [job ctx]
    (let [ctx-with-job (assoc ctx :job target)
          handle-error (fn [ex]
                         (log/error "Got job exception:" ex)
                         (assoc bc/failure
                                :exception ex
                                :message (.getMessage ex)))
          base-props {:start-time (u/now)
                      :credit-multiplier (cr/credit-multiplier target ctx)}]
      (log/debug "Executing event firing job:" (bc/job-id target))
      (md/chain
       (p/post-events events (job-start-evt
                              (-> ctx-with-job
                                  (update :job
                                          merge base-props {:status :running}))))
       (fn [_]
         ;; Catch both sync and async errors
         (try 
           (-> (j/execute! target ctx-with-job)
               (md/catch handle-error))
           (catch Exception ex
             (handle-error ex))))
       (fn [r]
         (log/debug "Job ended with response:" r)
         (md/chain
          (p/post-events events (job-end-evt
                                 (update ctx-with-job :job
                                         merge base-props {:end-time (u/now)})
                                 r))
          (constantly r)))))))

(defn- with-fire-events
  "Wraps job so events are fired on start and end."
  [events job]
  (map->EventFiringJob (-> (j/job->event job)
                           (assoc :target job
                                  :events events))))

(def with-extensions
  "Wraps the job so any registered extensions get executed."
  ext/wrap-job)

(defn- pipeline-filter [pipeline]
  [[{:label "pipeline"
     :value pipeline}]])

(defn run-all-jobs
  "Executes all jobs in the set, in dependency order."
  [{:keys [pipeline events] :as rt} jobs]
  (let [pf (cond->> jobs
             ;; Filter jobs by pipeline, if given
             pipeline (j/filter-jobs (j/label-filter (pipeline-filter pipeline)))
             true (map (comp (partial with-fire-events events) with-extensions)))]
    (log/debug "Found" (count pf) "matching jobs:" (map bc/job-id pf))
    (let [result @(j/execute-jobs! pf (rt->context rt))]
      (log/debug "Jobs executed, result is:" result)
      {:status (if (some (comp bc/failed? :result) (vals result)) :failure :success)
       :jobs result})))

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

(defn- with-script-evt
  "Creates an skeleton event with the script and invokes `f` on it"
  [rt f]
  (-> rt
      (base-event nil)
      (assoc :script (-> rt rs/build build/script))
      (f)))

(defn- job->evt [job]
  (select-keys job [j/job-id j/deps j/labels]))

(defn- script-start-evt [rt jobs]
  (letfn [(mark-pending [job]
            (assoc job :status :pending))]
    (with-script-evt rt
      #(-> %
           (assoc :type :script/start
                  :message "Script started")
           ;; Add all info we already have about jobs
           (assoc-in [:script :jobs] (->> jobs
                                          (map (fn [{:keys [id] :as job}]
                                                 [id job]))
                                          (into {})
                                          (mc/map-vals (comp mark-pending job->evt))))))))

(defn- script-end-evt [rt jobs res]
  (with-script-evt rt
    (fn [evt]
      (-> evt 
          (assoc :type :script/end
                 :message "Script completed")
          ;; FIXME Jobs don't contain all info here, as they should (like start and end time)
          (assoc-in [:script :jobs]
                    (mc/map-vals (fn [r]
                                   (-> (select-keys (:result r) [:status :message])
                                       (merge (job->evt (:job r)))))
                                 (:jobs res)))))))

(def run-all-jobs*
  (wrapped run-all-jobs
           script-start-evt
           script-end-evt))

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
    (s/valid? ::sb/build build)
    (log/debug "Executing script for build" build-id "at:" script-dir)
    (log/debug "Build map:" build)
    (try
      (let [jobs (load-jobs build (rt->context rt))]
        (log/trace "Jobs:" jobs)
        (log/debug "Loaded" (count jobs) "jobs:" (map bc/job-id jobs))
        (run-all-jobs* rt jobs))
      (catch Exception ex
        (log/error "Unable to load build script" ex)
        (let [msg ((some-fn (comp ex-message ex-cause)
                            ex-message) ex)]
          (ec/post-events (:events rt)
                          [(-> (base-event rt :script/end)
                               (assoc :script (build/script build)
                                      :message msg))])
          (assoc bc/failure
                 :message msg
                 :exception ex))))))
