(ns monkey.ci.build.core
  "Core build script functionality"
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [monkey.ci.spec]))

(def success {:status :success})
(def failure {:status :failure})

(defn initial-context [p]
  (assoc success
         :env {}
         :pipeline p))

(defn success? [{:keys [status]}]
  (= :success status))

(def failed? (complement success?))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [{:keys [steps] :as p}]
  (reduce (fn [ctx s]
            (let [r (-> ctx
                        (assoc :step s)
                        (s))]
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
  (run-steps! config))

