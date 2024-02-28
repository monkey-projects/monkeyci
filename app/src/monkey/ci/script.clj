(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [martian
             [core :as martian]
             [httpkit :as mh]
             [interceptors :as mi]]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [artifacts :as art]
             [build :as build]
             [cache :as cache]
             [containers :as c]
             [jobs :as j]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.containers
             [docker]
             [oci]
             [podman]]
            [monkey.ci.events.core :as ec]
            [org.httpkit.client :as http])
  (:import java.nio.channels.SocketChannel
           [java.net UnixDomainSocketAddress StandardProtocolFamily]))

(defn job-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defn- script-evt [evt rt]
  (assoc evt
         :src :script
         :sid (get-in rt [:build :sid])
         :time (System/currentTimeMillis)))

(defn- post-event [ctx evt]
  (log/trace "Posting event:" evt)
  (if-let [c (get-in ctx [:api :client])]
    (let [{:keys [status] :as r} @(martian/response-for c :post-event (script-evt evt ctx))]
      (when-not (= 202 status)
        (log/warn "Failed to post event, got status" status)
        (log/debug "Full response:" r)))
    (log/warn "Unable to post event, no client configured")))

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
    (fn [ctx & more]
      (apply w (assoc ctx :events {:poster (partial post-event ctx)}) more))))

;; (defmethod run-step ::container
;;   ;; Runs the step in a new container.  How this container is executed depends on
;;   ;; the configuration passed in from the parent process, specified in the context.
;;   [ctx]
;;   (let [{:keys [exit] :as r} (->> (c/run-container ctx)
;;                                   (merge bc/failure))]
;;     (cond-> r
;;       (= 0 exit) (merge bc/success))))

;; (defmethod run-step ::action
;;   ;; Runs a step as an action.  The action property of a step should be a
;;   ;; function that either returns a status result, or a new step configuration.
;;   [{:keys [step] :as ctx}]
;;   (let [f (:action step)]
;;     (log/debug "Executing function:" f)
;;     ;; If a step returns nil, treat it as success
;;     (let [r (or ((comp deref
;;                        (-> f
;;                            (art/wrap-artifacts)
;;                            (cache/wrap-caches))) ctx)
;;                 bc/success)]
;;       (if (bc/status? r)
;;         r
;;         ;; Recurse
;;         (run-step (assoc ctx :step (-> step
;;                                        (dissoc :action)
;;                                        (merge (->map r)))))))))

;; (defn- make-job-dir-absolute
;;   "Rewrites the job dir in the context so it becomes an absolute path, calculated
;;    relative to the checkout dir.

;;    Should be moved to job implementations, since this is only useful for action jobs."
;;   [{:keys [job] :as ctx}]
;;   (if (map? job)
;;     (let [checkout-dir (build/build-checkout-dir ctx)]
;;       (update-in ctx [:job :work-dir]
;;                  (fn [d]
;;                    (if d
;;                      (u/abs-path checkout-dir d)
;;                      checkout-dir))))
;;     ctx))

;; (defn- run-single-job
;;   "Runs a single job using the configured runner"
;;   [rt]
;;   (let [{:keys [job] :as rt} (make-job-dir-absolute rt)]
;;     (try
;;       (log/debug "Running job:" job)
;;       #_(run-job ctx)
;;       (j/execute! job rt)
;;       (catch Exception ex
;;         (log/warn "Job failed:" (.getMessage ex))
;;         (assoc bc/failure :exception ex)))))

;; (defn- with-pipeline [{:keys [pipeline] :as ctx} evt]
;;   (let [p (select-keys pipeline [:name :index])]
;;     (-> evt
;;         (assoc :index (get-in ctx [:job :index])
;;                :pipeline p)
;;         (script-evt ctx))))

(defn- job-start-evt [{:keys [job]}]
  (cond-> {:type :job/start
           :id (bc/job-id job)
           :message "Job started"}))

(defn- job-end-evt [{:keys [job]} {:keys [status message exception]}]
  (cond-> {:type :job/end
           :message (or message
                        "Job completed")
           :id (bc/job-id job)
           :status status}
    (some? exception) (assoc :message (.getMessage exception)
                             :stack-trace (u/stack-trace exception))))

;; (def run-single-job*
;;   ;; TODO Send the start event only when the job has been fully resolved?
;;   (wrapped run-single-job
;;            job-start-evt
;;            job-end-evt))

;; (defn- log-result [r]
;;   (log/debug "Result:" r)
;;   (when-let [o (:output r)]
;;     (log/debug "Output:" o))
;;   (when-let [o (:error r)]
;;     (log/warn "Error output:" o)))

;; (defn- run-jobs!
;;   "Runs all jobs in sequence, stopping at the first failure.
;;    Returns the execution context."
;;   [initial-ctx idx {:keys [name jobs] :as p}]
;;   (log/info "Running pipeline:" name)
;;   (log/debug "Running pipeline jobs:" p)
;;   (->> jobs
;;        ;; Add index to each job
;;        (map (fn [i s]
;;               (assoc s :index i))
;;             (range))     
;;        (reduce (fn [ctx s]
;;                  (let [r (-> ctx
;;                              (assoc :job s :pipeline (assoc p :index idx))
;;                              (run-single-job*))]
;;                    (log-result r)
;;                    (cond-> ctx
;;                      true (assoc :status (:status r)
;;                                  :last-result r)
;;                      (bc/failed? r) (reduced))))
;;                (merge (job-context p) initial-ctx))))

;; Wraps a job so it fires an event before and after execution, and also
;; catches any exceptions.
(defrecord EventFiringJob [target]
  j/Job
  (execute! [job rt]
    (let [rt-with-job (assoc rt :job target)
          handle-error (fn [ex]
                         (assoc bc/failure :exception ex))]
      (md/chain
       (rt/post-events rt (job-start-evt rt-with-job))
       (fn [_]
         ;; Catch both sync and async errors
         (try 
           (-> (j/execute! target rt-with-job)
               (md/catch handle-error))
           (catch Exception ex
             (handle-error ex))))
       (fn [r]
         (md/chain
          (rt/post-events rt (job-end-evt rt-with-job r))
          (constantly r)))))))

(defn- with-fire-events
  "Wraps job so events are fired on start and end."
  [job]
  (map->EventFiringJob (-> (select-keys job [:id :dependencies :labels])
                           (assoc :target job))))

(defn- pipeline-filter [pipeline]
  [[{:label "pipeline"
     :value pipeline}]])

(defn run-all-jobs
  "Executes all jobs in the set, in dependency order."
  [{:keys [pipeline] :as rt} jobs]
  (let [pf (cond->> jobs
             ;; Filter jobs by pipeline, if given
             pipeline (j/filter-jobs (j/label-filter (pipeline-filter pipeline)))
             true (map with-fire-events))]
    (log/debug "Found" (count pf) "matching jobs:" (map bc/job-id pf))
    (let [result @(j/execute-jobs! pf rt)]
      {:status (if (every? (comp bc/success? :result) (vals result)) :success :failure)
       :jobs result})))

;;; Script client functions
;;; TODO Replace this with a more generic approach (possibly using ZeroMQ sockets)

(defn- make-uds-address [path]
  (UnixDomainSocketAddress/of path))

(defn- open-uds-socket []
  (SocketChannel/open StandardProtocolFamily/UNIX))

;; The swagger is fetched by the build script client api
(def swagger-path "/script/swagger.json")

(defn- connect-to-uds [path]
  (let [client (http/make-client
                {:address-finder (fn make-addr [_]
                                   (make-uds-address path))
                 :channel-factory (fn [_]
                                    (open-uds-socket))})
        ;; Martian doesn't pass in the client in the requests, so do it with an interceptor.
        client-injector {:name ::inject-client
                         :enter (fn [ctx]
                                  (assoc-in ctx [:request :client] client))}
        interceptors (-> mh/default-interceptors
                         (mi/inject client-injector :before ::mh/perform-request))]
    ;; Url is not used, but we need the path to the swagger
    (mh/bootstrap-openapi (str "http://fake-host" swagger-path)
                          {:interceptors interceptors}
                          {:client client})))

(defn- connect-to-host [url]
  (mh/bootstrap-openapi (str url swagger-path)))

(defn make-client
  "Initializes a Martian client using the configuration given.  It can either
   connect to a domain socket, or a host.  The client is then added to the
   context, where it can be accessed by the build scripts."
  [{{:keys [url socket]} :api}]
  (log/debug "Connecting to API at" (or url socket))
  (cond
    url (connect-to-host url)
    socket (connect-to-uds socket)))

(def valid-config? (some-fn :socket :url))

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

(defn- with-script-dir [{:keys [script-dir] :as ctx} evt]
  (-> (assoc evt :dir script-dir)
      (script-evt ctx)))

(defn- script-started-evt [ctx _]
  (with-script-dir ctx
    {:type :script/start
     :message "Script started"}))

(defn- script-completed-evt [ctx & _]
  (with-script-dir ctx
    {:type :script/end
     :message "Script completed"}))

(def run-all-jobs*
  (wrapped run-all-jobs
           script-started-evt
           script-completed-evt))

(def pipeline? bc/pipeline?)

(defn- add-dependencies
  "Given a sequence of jobs from a pipeline, makes each job dependent on the previous one."
  [jobs]
  (log/debug "Adding dependencies to" (count jobs) "jobs")
  (reduce (fn [r j]
            (conj r (cond-> j
                      (not-empty r)
                      (update :dependencies (comp vec distinct conj) (:id (last r))))))
          []
          jobs))

(defn- assign-ids
  "Assigns an id to each job that does not have one already."
  [jobs]
  (letfn [(assign-id [x id]
            (if (nil? (bc/job-id x))
              (if (map? x)
                (assoc x :id id)
                (with-meta x (assoc (meta x) :job/id id)))
              x))]
    (map-indexed (fn [i {:keys [id] :as j}]
                   (cond-> j
                     (nil? id) (assign-id (format "job-%d" (inc i)))))
                 jobs)))

(defn- add-pipeline-name-lbl [{:keys [name]} jobs]
  (cond->> jobs
    name (map #(assoc-in % [j/labels "pipeline"] name))))

(defn pipeline->jobs
  "Converts a pipeline in a set of jobs"
  [rt p]
  (->> (:jobs p)
       (j/resolve-all rt)
       (add-dependencies)
       (assign-ids)
       (add-pipeline-name-lbl p)))

(defn resolve-jobs
  "The build script either returns a list of pipelines, a set of jobs or a function 
   that returns either.  This function resolves the jobs by processing the script
   return value."
  [p rt]
  (cond
    (pipeline? p) (pipeline->jobs rt p)
    (sequential? p) (mapcat #(resolve-jobs % rt) p)
    (fn? p) (resolve-jobs (p rt) rt)
    (var? p) (resolve-jobs (var-get p) rt)
    :else (remove nil? p)))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is executed in 
   this same process."
  [{:keys [script-dir] :as rt}]
  (let [build-id (build/get-build-id rt)]
    (log/debug "Executing script for build" build-id "at:" script-dir)
    (log/debug "Script runtime:" rt)
    (try 
      (let [jobs (-> (load-script script-dir build-id)
                     (resolve-jobs rt))]
        (log/debug "Jobs:" jobs)
        (log/debug "Loaded" (count jobs) "jobs:" (map bc/job-id jobs))
        (run-all-jobs* rt jobs))
      (catch Exception ex
        (log/error "Unable to load build script" ex)
        (post-event rt {:type :script/end
                        :message (.getMessage ex)})
        bc/failure))))
