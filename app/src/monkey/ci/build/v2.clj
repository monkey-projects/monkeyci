(ns monkey.ci.build.v2
  "A group of functions written on top of the more low-level build functions, meant
   to improve writing build scripts.  They should make the build scripts more intuitive,
   more readable.  And, dare I say it, prettier?

   The general intention is to provide functions for most purposes, without the user
   having to resort to using keywords and maps."
  
  (:require [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as co]
             [jobs :as j]]))

(def action-job
  "Declares an action job with id, action and options"
  bc/action-job)

(def action-job?
  "Checks if argument is an action job"
  bc/action-job?)

(defn container-job
  "Declares an container job with id and container options"
  [id & [opts]]
  (bc/container-job id (or opts {})))

(def container-job?
  "Checks if argument is an container job"
  bc/container-job?)

(def dependencies :dependencies)

(defn- try-unwrap
  "Applies `f` to the job.  If `job` is a function, returns a new function 
   that applies `f` to the result of the function."
  [job f & args]
  (if (fn? job)
    (fn [& job-args]
      (apply try-unwrap (apply job job-args) f args))
    (apply f job args)))

(defn depends-on
  "Adds dependencies to the given job.  Does not overwrite existing dependencies."
  [job & deps]
  (try-unwrap job bc/depends-on (flatten deps)))

(defn image
  "Gets or sets container image"
  ([job]
   (co/image job))
  ([job img]
   (try-unwrap job assoc :container/image img)))

(defn- set-env [job e]
  (cond
    (action-job? job) (assoc job :env e)
    (container-job? job) (assoc job co/env e)))

(defn env
  "Gets or sets environment vars for the job"
  ([job]
   (or (co/env job) (:env job)))
  ([job e]
   (try-unwrap job set-env e)))

(defn work-dir
  "Gets or sets work dir for the job"
  ([job]
   (:work-dir job))
  ([job wd]
   (try-unwrap job assoc :work-dir wd)))

(defn artifact
  "Defines a new artifact with given id, located at given `path`.  This can be passed
   to `save-artifacts` or `restore-artifacts`."
  [id path]
  {:id id
   :path path})

(defn save-artifacts [job & arts]
  (try-unwrap job update :save-artifacts concat (flatten arts)))

(defn restore-artifacts [job & arts]
  (try-unwrap job update :restore-artifacts concat (flatten arts)))

(def cache
  "Defines a cache with given id an path, that can be passed to `caches`"
  artifact)

(defn caches [job & arts]
  (try-unwrap job update :caches concat (flatten arts)))

(defn- file-test [tester]
  (fn test-fn
    ([ctx pred]
     (some? (tester [ctx pred])))
    ([pred]
     (fn [ctx]
       (test-fn ctx pred)))))

(def added?
  "Checks if files have been added in this build using the given predicate, which can either be a
   regex, a string or a matcher function."
  (file-test bc/added?))

(def removed?
  "Checks if files have been removed in this build using the given predicate, which can either be a
   regex, a string or a matcher function."
  (file-test bc/removed?))

(def modified?
  "Checks if files have been modified in this build using the given predicate, which can either be a
   regex, a string or a matcher function."
  (file-test bc/modified?))

(def tag
  "Gets commit tag (if any) from the context."
  bc/tag)

(def branch
  "Gets commit branch from the context."
  bc/branch)

(def main-branch
  "Gets configured main branch from the build."
  bc/main-branch)

(def main-branch?
  "Checks if the build branch is the configured main branch."
  bc/main-branch)
