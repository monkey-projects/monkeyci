(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as c]
             [docker :as d]
             [events :as e]
             [utils :as u]]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]))

(defn initial-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defn- post-event [ctx evt]
  (some-> (get-in ctx [:events :bus]) 
          (e/post-event (assoc evt :src :script))))

(defn- wrap-events
  "Posts event before and after invoking `f`"
  [ctx before-evt after-evt f]
  (try
    (post-event ctx before-evt)
    (f)
    (finally
      (post-event ctx after-evt))))

(defprotocol PipelineStep
  (run-step [s ctx]))

(defn- run-container-step
  "Runs the step in a new container.  How this container is executed depends on
   the configuration passed in from the parent process, specified in the context."
  [ctx]
  (let [{:keys [exit] :as r} (->> (c/run-container ctx)
                                  (merge bc/failure))]
    (cond-> r
      (zero? exit) (merge bc/success))))

(extend-protocol PipelineStep
  clojure.lang.Fn
  (run-step [f ctx]
    (log/debug "Executing function:" f)
    ;; If a step returns nil, treat it as success
    (or (f ctx) bc/success))

  clojure.lang.IPersistentMap
  (run-step [{:keys [action] :as step} ctx]
    ;; TODO Make more generic
    (cond
      (some? (:container/image step))
      (run-container-step ctx)
      (some? action)
      (run-step action ctx)
      :else
      (throw (ex-info "invalid step configuration" {:step step})))))

(defn- make-step-dir-absolute [{:keys [work-dir step] :as ctx}]
  ;; TODO Make more generic
  (if (map? step)
    (update-in ctx [:step :work-dir]
               (fn [d]
                 (if d
                   (u/abs-path work-dir d)
                   work-dir)))
    ctx))

(defn- run-step*
  "Runs a single step using the configured runner"
  [ctx]
  (wrap-events
   ctx
   {:type :step/start
    :message "Step started"}
   {:type :step/end
    :message "Step completed"}
   (fn []
     (let [{:keys [work-dir step] :as ctx} (make-step-dir-absolute ctx)]
       (try
         (log/debug "Running step:" step)
         (run-step step ctx)
         (catch Exception ex
           (log/warn "Step failed:" (.getMessage ex))
           (assoc bc/failure :exception ex)))))))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [initial-ctx {:keys [name steps] :as p}]
  (wrap-events
   initial-ctx
   {:type :pipeline/start
    :pipeline name
    :message "Starting pipeline"}
   {:type :pipeline/end
    :pipeline name
    :message "Completed pipeline"}
   (fn []
     (log/info "Running pipeline:" name)
     (log/debug "Running pipeline steps:" p)
     (reduce (fn [ctx s]
               (let [r (-> ctx
                           (assoc :step s)
                           (run-step*))]
                 (log/debug "Result:" r)
                 (when-let [o (:output r)]
                   (log/debug "Output:" o))
                 (when-let [o (:error r)]
                   (log/warn "Error output:" o))
                 (cond-> ctx
                   true (assoc :status (:status r)
                               :last-result r)
                   (bc/failed? r) (reduced))))
             (merge (initial-context p) initial-ctx)
             steps))))

(defn run-pipelines
  "Executes the pipelines by running all steps sequentially.  Currently,
   pipelines are executed sequentially too, but this could be converted 
   into parallel processing."
  [{:keys [pipeline] :as ctx} p]
  (let [p (cond->> (if (vector? p) p [p])
            ;; Filter pipeline by name, if given
            pipeline (filter (comp (partial = pipeline) :name)))]
    (log/debug "Found" (count p) "pipelines")
    (let [result (->> p
                      (map (partial run-steps! ctx))
                      (doall))]
      {:status (if (every? bc/success? result) :success :failure)})))

(defn- load-pipelines [dir]
  (let [tmp-ns (symbol (str "build-" (random-uuid)))]
    ;; FIXME I don't think this is a very robust approach, find a better way.
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

(defn- make-socket-bus [p]
  (log/debug "Sending events to socket at" p)
  (let [addr (uds/make-address p)
        conn (uds/connect-socket addr)
        {:keys [channel] :as bus} (e/make-bus)]
    (sa/write-from-channel channel conn)
    {:addr addr
     :socket conn
     :bus bus}))

(defn- make-dummy-bus []
  (log/debug "No event socket configured, events will not be passed to controlling process")
  {:bus (e/make-bus (ca/chan (ca/sliding-buffer 1)))})

(defn- setup-event-bus
  "Configures the event bus for the event socket.  If no socket is specified,
   then the bus will still be configured, but it will drop all events."
  [{:keys [event-socket] :as ctx}]
  (assoc ctx :events (if event-socket
                       (make-socket-bus event-socket)
                       (make-dummy-bus))))

(defn- close-socket [ctx]
  (when-let [s (get-in ctx [:events :socket])]
    (uds/close s)))

(defn- with-event-bus [ctx f]
  (let [ctx (setup-event-bus ctx)]
    (try
      (f ctx)
      (finally
        (close-socket ctx)))))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [{:keys [script-dir] :as ctx}]
  (with-event-bus ctx
    (fn [ctx]
      (log/debug "Executing script at:" script-dir)
      (let [p (load-pipelines script-dir)]
        (wrap-events
         ctx
         {:type :script/started
          :message "Script started"
          :dir script-dir}
         {:type :script/finished
          :message "Script completed"
          :dir script-dir}
         (fn []
           (log/debug "Loaded pipelines:" p)
           (run-pipelines ctx p)))))))
