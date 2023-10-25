(ns monkey.ci.build.core
  "Core build script functionality.  This is used by build scripts to create
   the configuration which is then executed by the configured runner.  Which
   runner is configured or active depends on the configuration of the MonkeyCI
   application that executes the script."
  (:require [clojure.spec.alpha :as s]
            ;;[clojure.tools.logging :as log]
            [monkey.ci.build.spec]))

(defn status [v]
  {:status v})

(def success (status :success))
(def failure (status :failure))

(defn status?
  "Checks if the given object is a step status"
  [x]
  (some? (:status x)))

(defn success? [{:keys [status]}]
  (= :success status))

(def failed? (complement success?))

(defrecord Pipeline [steps name])

(defn pipeline
  "Create a pipeline with given config"
  [config]
  {:pre [(s/valid? :ci/pipeline config)]}
  (map->Pipeline config))

(defmacro defpipeline
  "Convenience macro that declares a var for a pipeline with the given name 
   with specified steps"
  [n steps]
  `(def ~n
     (pipeline
      {:name ~(name n)
       :steps ~steps})))
