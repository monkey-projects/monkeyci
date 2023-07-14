(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as bc]))

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

(defn- run-step*
  "Runs a single step using the configured runner"
  [{:keys [step] :as ctx}]
  (try
    (log/debug "Running step:" step)
    (run-step step ctx)
    (catch Exception ex
      (assoc bc/failure :exception ex))))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [{:keys [steps] :as p}]
  (log/debug "Running pipeline steps:" p)
  (reduce (fn [ctx s]
            (let [r (-> ctx
                        (assoc :step s)
                        (run-step*))]
              (log/debug "Result:" r)
              (when-let [o (:output r)]
                (log/info "Output:" o))
              (cond-> ctx
                true (assoc :status (:status r)
                            :last-result r)
                (bc/failed? r) (reduced))))
          (initial-context p)
          steps))

(defn run-pipelines [p]
  (let [p (if (vector? p) p [p])]
    (log/debug "Found" (count p) "pipelines")
    (let [result (->> p
                      (map run-steps!)
                      (doall))]
      {:status (if (every? bc/success? result) :success :failure)})))

(defn- load-pipelines [dir]
  (let [tmp-ns (symbol (str "build-" (random-uuid)))]
    ;; I don't think this is a very good approach
    (in-ns tmp-ns)
    (clojure.core/use 'clojure.core)
    (try
      (let [path (io/file dir "build.clj")]
        (log/info "Loading script:" path)
        ;; This should return pipelines to run
        (load-file (str path)))
      (catch Exception ex
        (log/error "Failed to execute script" ex)
        (assoc bc/failure :exception ex))
      (finally
        ;; Return
        (in-ns 'monkey.ci.script)
        (remove-ns tmp-ns)))))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [dir]
  (log/debug "Executing script at:" dir)
  (let [p (load-pipelines dir)]
    (log/debug "Loaded pipelines:" p)
    (run-pipelines p)))
