(ns monkey.ci.build.v2
  "A group of functions written on top of the more low-level build functions, meant
   to improve writing build scripts.  They should make the build scripts more intuitive,
   more readable.  And, dare I say it, prettier?

   The general intention is to provide functions for most purposes, without the user
   having to resort to using keywords and maps."
  
  (:require [monkey.ci.build.core :as bc]
            [monkey.ci.containers :as co]))

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

(defn artifact
  "Defines a new artifact with given id, located at given `path`.  This can be passed
   to `save-artifacts` or `restore-artifacts`."
  [id path]
  {:id id
   :path path})
