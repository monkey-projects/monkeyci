(ns monkey.ci.build.core
  "Core build script functionality.  This is used by build scripts to create
   the configuration which is then executed by the configured runner.  Which
   runner is configured or active depends on the configuration of the MonkeyCI
   application that executes the script."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [monkey.ci.build.spec]))

(defn status [v]
  {:status v})

(def success (status :success))
(def failure (status :failure))

(defn initial-context [p]
  (assoc success
         :env {}
         :pipeline p))

(defn success? [{:keys [status]}]
  (= :success status))

(def failed? (complement success?))

#_(defrecord LocalRunner []
  sr/StepRunner
  (run-step [this {:keys [step] :as ctx}]
    (step ctx)))

#_(def default-runner (->LocalRunner))

#_(defn- run-step
  "Runs a single step using the configured runner"
  [ctx]
  (let [runner (get-in ctx [:pipeline :runner] default-runner)]
    (sr/run-step runner ctx)))

#_(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [{:keys [steps] :as p}]
  (reduce (fn [ctx s]
            (let [r (-> ctx
                        (assoc :step s)
                        (run-step))]
              (log/debug "Result:" r)
              (when-let [o (:output r)]
                (log/info "Output:" o))
              (cond-> ctx
                true (assoc :status (:status r)
                            :last-result r)
                (failed? r) (reduced))))
          (initial-context p)
          steps))

(defn pipeline
  "Runs the pipeline with given config"
  [config]
  {:pre [(s/valid? :ci/pipeline config)]}
  #_(run-steps! config))
