(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as bc]
            [monkey.ci.utils :as u]))

(defn initial-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defprotocol PipelineStep
  (run-step [s ctx]))

(extend-protocol PipelineStep
  clojure.lang.Fn
  (run-step [f ctx]
    (log/debug "Executing function:" f)
    ;; If a step returns nil, treat it as success
    (or (f ctx) bc/success))

  clojure.lang.IPersistentMap
  (run-step [{:keys [action]} ctx]
    (run-step action ctx)))

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
        (assoc bc/failure :exception ex)))))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [initial-ctx {:keys [steps] :as p}]
  (log/debug "Running pipeline steps:" p)
  (reduce (fn [ctx s]
            (let [r (-> ctx
                        (assoc :step s)
                        (run-step*))]
              (log/debug "Result:" r)
              (when-let [o (:output r)]
                (log/debug "Output:" o))
              (cond-> ctx
                true (assoc :status (:status r)
                            :last-result r)
                (bc/failed? r) (reduced))))
          (merge (initial-context p) initial-ctx)
          steps))

(defn run-pipelines [ctx p]
  (let [p (if (vector? p) p [p])]
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
  [{:keys [work-dir script-dir] :as ctx}]
  (log/debug "Executing script at:" script-dir)
  (let [p (load-pipelines script-dir)]
    (log/debug "Loaded pipelines:" p)
    (run-pipelines ctx p)))
