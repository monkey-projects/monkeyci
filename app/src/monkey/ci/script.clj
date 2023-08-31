(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as c]
             [docker :as d]
             [utils :as u]]))

(defn initial-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defprotocol PipelineStep
  (run-step [s ctx]))

(defn- run-container-step
  "Runs the step in a new container.  How this container is executed depends on
   the configuration passed in from the parent process, specified in the context."
  [ctx]
  (c/run-container ctx))

(extend-protocol PipelineStep
  clojure.lang.Fn
  (run-step [f ctx]
    (log/debug "Executing function:" f)
    ;; If a step returns nil, treat it as success
    (or (f ctx) bc/success))

  clojure.lang.IPersistentMap
  (run-step [{:keys [action] :as step} ctx]
    (cond
      (some? action)
      (run-step action ctx)
      (some? (:container/image step))
      (run-container-step ctx)
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
  (let [{:keys [work-dir step] :as ctx} (make-step-dir-absolute ctx)]
    (try
      (log/debug "Running step:" step)
      (run-step step ctx)
      (catch Exception ex
        (log/warn "Step failed:" (.getMessage ex))
        (assoc bc/failure :exception ex)))))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [initial-ctx {:keys [name steps] :as p}]
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
          steps))

(defn run-pipelines [{:keys [pipeline] :as ctx} p]
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
    ;; FIXME I don't think this is a very good approach, find a better way.
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

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [{:keys [script-dir] :as ctx}]
  (log/debug "Executing script at:" script-dir)
  (let [p (load-pipelines script-dir)]
    (log/debug "Loaded pipelines:" p)
    (run-pipelines ctx p)))
