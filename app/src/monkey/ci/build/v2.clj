(ns monkey.ci.build.v2
  "A group of functions written on top of the more low-level build functions, meant
   to improve writing build scripts.  They should make the build scripts more intuitive,
   more readable.  And, dare I say it, prettier?

   The general intention is to provide functions for most purposes, without the user
   having to resort to using keywords and maps."
  
  (:require [monkey.ci.build.core :as bc]))

(def action-job
  "Declares an action job with id, action and options"
  bc/action-job)

(def action-job?
  "Checks if argument is an action job"
  bc/action-job?)

(def dependencies :dependencies)

(defn depends-on
  "Adds dependencies to the given job.  Does not overwrite existing dependencies."
  [job & deps]
  (if (fn? job)
    (fn [ctx]
      (-> (job ctx)
          (depends-on deps)))
    (update job :dependencies (comp vec distinct concat) (flatten deps))))
