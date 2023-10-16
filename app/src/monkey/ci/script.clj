(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [martian
             [core :as martian]
             [httpkit :as mh]]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as c]
             [docker]
             [events :as e]
             [podman]
             [utils :as u]]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]
            [org.httpkit.client :as http]))

(defn initial-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defn- post-event [ctx evt]
  (when-not (some-> (get-in ctx [:api :client]) 
                    (martian/response-for :post-event (assoc evt :src :script)))
    (log/warn "Unable to post event")))

(defn- wrap-events
  "Posts event before and after invoking `f`"
  [ctx before-evt after-evt f]
  (try
    (post-event ctx before-evt)
    (let [r (f)]
      (post-event ctx (if (fn? after-evt)
                        (after-evt r)
                        after-evt))
      r)
    (catch Exception ex
      (post-event ctx (assoc after-evt :exception ex)))))

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

(defn- make-step-dir-absolute [{:keys [checkout-dir step] :as ctx}]
  ;; TODO Make more generic
  (if (map? step)
    (update-in ctx [:step :work-dir]
               (fn [d]
                 (if d
                   (u/abs-path checkout-dir d)
                   checkout-dir)))
    ctx))

(defn ->map [s]
  (if (map? s)
    s
    {:action s}))

(defn- run-step*
  "Runs a single step using the configured runner"
  [{{:keys [name]} :step :as ctx}]
  (wrap-events
   ctx
   (cond-> {:type :step/start
            :message "Step started"}
     (some? name) (assoc :name name))
   (fn [{:keys [status]}]
     {:type :step/end
      :message "Step completed"
      :status status})
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
  [initial-ctx idx {:keys [name steps] :as p}]
  (wrap-events
   initial-ctx
   {:type :pipeline/start
    :pipeline name
    :message "Starting pipeline"}
   (fn [r]
     {:type :pipeline/end
      :pipeline name
      :message "Completed pipeline"
      :status (:status r)})
   (fn []
     (log/info "Running pipeline:" name)
     (log/debug "Running pipeline steps:" p)
     (->> steps
          (map ->map)
          ;; Add index to each step
          (map (fn [i s]
                 (assoc s :index i))
               (range))     
          (reduce (fn [ctx s]
                    (let [r (-> ctx
                                (assoc :step s :pipeline (assoc p :index idx))
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
                  (merge (initial-context p) initial-ctx))))))

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
                      (map-indexed (partial run-steps! ctx))
                      (doall))]
      {:status (if (every? bc/success? result) :success :failure)})))

(defn- load-pipelines [dir build-id]
  (let [tmp-ns (symbol (or build-id (str "build-" (random-uuid))))]
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

(defn- setup-api-client [ctx]
  (let [socket (get-in ctx [:api :socket])
        client (http/make-client
                {:address-finder #(uds/make-address socket)
                 :channel-factory uds/connect-socket})]
    (cond-> ctx
      ;; In tests it could be there is no socket, so skip the initialization in that case
      socket (assoc-in [:api :client] (mh/bootstrap-openapi "http://fake" {:client client})))))

(defn- with-script-api [ctx f]
  (let [ctx (setup-api-client ctx)]
    (f ctx)))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [{:keys [script-dir build-id] :as ctx}]
  (with-script-api ctx
    (fn [ctx]
      (log/debug "Executing script for build" build-id "at:" script-dir)
      (let [p (load-pipelines script-dir build-id)]
        (wrap-events
         ctx
         {:type :script/start
          :message "Script started"
          :dir script-dir}
         {:type :script/end
          :message "Script completed"
          :dir script-dir}
         (fn []
           (log/debug "Loaded pipelines:" p)
           (run-pipelines ctx p)))))))
