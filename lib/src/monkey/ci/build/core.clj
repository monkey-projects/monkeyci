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
(def skipped (status :skipped))

(defn status?
  "Checks if the given object is a step status"
  [x]
  (some? (:status x)))

(defn success? [{:keys [status]}]
  (or (nil? status)
      (= :success status)))

(defn failed? [{:keys [status]}]
  (= :failure status))

(defn skipped? [{:keys [status]}]
  (= :skipped status))

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

(defn git-ref
  "Gets the git ref from the context"
  [ctx]
  (get-in ctx [:build :git :ref]))

(def branch-regex #"^refs/heads/(.*)$")
(def tag-regex #"^refs/tags/(.*)$")

(defn ref-regex
  "Applies the given regex on the ref from the context, returns the matching groups."
  [ctx re]
  (some->> (git-ref ctx)
           (re-matches re)))

(def branch
  "Gets the commit branch from the context"
  (comp second #(ref-regex % branch-regex)))

(def main-branch (comp :main-branch :git :build))

(defn main-branch? [ctx]
  (= (main-branch ctx)
     (branch ctx)))

(def tag
  "Gets the commit tag from the context"
  (comp second #(ref-regex % tag-regex)))
