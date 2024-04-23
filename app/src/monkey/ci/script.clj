(ns monkey.ci.script
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [artifacts :as art]
             [build :as build]
             [cache :as cache]
             [containers :as c]
             [extensions :as ext]
             [jobs :as j]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.containers
             [docker]
             [oci]
             [podman]]
            [monkey.ci.events.core :as ec])
  (:import java.nio.channels.SocketChannel
           [java.net UnixDomainSocketAddress StandardProtocolFamily]))

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
  "Creates an skeleton event with basic properties"
  [rt type]
  {:type type
   :src :script
   :sid (build/get-sid rt)
   :time (u/now)})

(defn- job-start-evt [{:keys [job] :as rt}]
  (-> (base-event rt :job/start)
      (assoc :job (j/job->event job)
             :message "Job started")))

(defn- job-end-evt [{:keys [job] :as rt} {:keys [status message exception]}]
  (-> (base-event rt :job/end)
      (assoc :message "Job completed"
             :job (cond-> (j/job->event job)
                    true (assoc :status status)
                    (some? exception) (assoc :message (or message (.getMessage exception))
                                             :stack-trace (u/stack-trace exception))))))

;; Wraps a job so it fires an event before and after execution, and also
;; catches any exceptions.
(defrecord EventFiringJob [target]
  j/Job
  (execute! [job rt]
    (let [rt-with-job (assoc rt :job target)
          handle-error (fn [ex]
                         (log/error "Got job exception:" ex)
                         (assoc bc/failure
                                :exception ex
                                :message (.getMessage ex)))
          st (u/now)]
      (log/debug "Executing event firing job:" (bc/job-id target))
      (md/chain
       (rt/post-events rt (job-start-evt (-> rt-with-job
                                             (assoc-in [:job :start-time] st))))
       (fn [_]
         ;; Catch both sync and async errors
         (try 
           (-> (j/execute! target rt-with-job)
               (md/catch handle-error))
           (catch Exception ex
             (handle-error ex))))
       (fn [r]
         (log/debug "Job ended with response:" r)
         (md/chain
          (rt/post-events rt (job-end-evt (update rt-with-job :job assoc
                                                  :start-time st
                                                  :end-time (u/now))
                                          r))
          (constantly r)))))))

(defn- with-fire-events
  "Wraps job so events are fired on start and end."
  [job]
  (map->EventFiringJob (-> (select-keys job [j/job-id j/deps j/labels])
                           (assoc :target job))))

(def with-extensions
  "Wraps the job so any registered extensions get executed."
  ext/wrap-job)

(defn- pipeline-filter [pipeline]
  [[{:label "pipeline"
     :value pipeline}]])

(defn run-all-jobs
  "Executes all jobs in the set, in dependency order."
  [{:keys [pipeline] :as rt} jobs]
  (let [pf (cond->> jobs
             ;; Filter jobs by pipeline, if given
             pipeline (j/filter-jobs (j/label-filter (pipeline-filter pipeline)))
             true (map (comp with-fire-events with-extensions)))]
    (log/debug "Found" (count pf) "matching jobs:" (map bc/job-id pf))
    (let [result @(j/execute-jobs! pf rt)]
      (log/debug "Jobs executed, result is:" result)
      {:status (if (some (comp bc/failed? :result) (vals result)) :failure :success)
       :jobs result})))

;;; Script client functions

(defn make-client
  "Creates an API client function, that can be invoked by build scripts to 
   perform certain operations, like retrieve build parameters.  The client
   uses the token passed by the spawning process to gain access to those
   resources."
  [{{:keys [url token]} :api}]
  (letfn [(throw-on-error [{:keys [status] :as r}]
            (if (>= status 400)
              (md/error-deferred (ex-info "Failed to invoke API call" r))
              r))
          (parse-body [{:keys [body]}]
            (with-open [r (bs/to-reader body)]
              (u/parse-edn r)))]
    (log/debug "Connecting to API at" url)
    (fn [req]
      (-> req
          (update :url (partial str url))
          (assoc-in [:headers "authorization"] (str "Bearer " token))
          (assoc-in [:headers "accept"] "application/edn")
          (http/request)
          (md/chain
           throw-on-error
           parse-body)))))

(def valid-config? (every-pred :url :token))

(defmethod rt/setup-runtime :api [conf _]
  (when-let [c (:api conf)]
    (when (valid-config? c)
      {:client (make-client conf)})))

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
      (assoc :script (-> rt rt/build build/script))
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
  [rt]
  (-> (load-script (build/rt->script-dir rt) (build/get-build-id rt))
      (resolve-jobs rt)))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is executed in 
   this same process."
  [rt]
  (let [build-id (build/get-build-id rt)
        script-dir (build/rt->script-dir rt)]
    (log/debug "Executing script for build" build-id "at:" script-dir)
    (log/debug "Script runtime:" rt)
    (try 
      (let [jobs (load-jobs rt)]
        (log/trace "Jobs:" jobs)
        (log/debug "Loaded" (count jobs) "jobs:" (map bc/job-id jobs))
        (run-all-jobs* rt jobs))
      (catch Exception ex
        (log/error "Unable to load build script" ex)
        (let [msg ((some-fn (comp ex-message ex-cause)
                            ex-message) ex)]
          (rt/post-events rt [(-> (base-event rt :script/end)
                                  (assoc :script (-> rt rt/build build/script)
                                         :message msg))])
          (assoc bc/failure
                 :message msg
                 :exception ex))))))
