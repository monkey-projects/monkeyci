(ns monkey.ci.api
  "Api functions, which can be invoked by build scripts.  These often delegate
   to more specific namespaces, but this namespace is provided as a convenient
   entry point.

   The general intention is to provide functions for most purposes, without the user
   having to resort to using keywords and maps."

  (:require [babashka.fs :as fs]
            [monkey.ci.build
             [api :as api]
             [core :as bc]
             [shell :as bs]]
            [monkey.ci
             [build :as b]
             [containers :as co]
             [jobs :as j]]))

(def action-job
  "Declares an action job with id, action and options"
  bc/action-job)

(def action-job?
  "Checks if argument is an action job"
  bc/action-job?)

;; Job return values
(def success "Indicates the job was successful"
  bc/success)

(def failure
  "Indicates the job has failed"
  bc/failure)

(def error
  "Same as `failure`"
  failure)

(def failed
  "Same as `failure`"
  failure)

(def skipped
  "Indicates the job was intentionally skipped"
  bc/skipped)

(def with-message
  "Sets a human-readable message on the return value"
  bc/with-message)

(defn container-job
  "Declares an container job with id and container options"
  [id & [opts]]
  (bc/container-job id (or opts {})))

(def container-job?
  "Checks if argument is an container job"
  bc/container-job?)

(def job-id
  "Gets the id of the given job"
  bc/job-id)

(def dependencies
  "Gets the configured dependencies of a job"
  :dependencies)

(defn ^:no-doc try-unwrap
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

(defn script
  "Gets or sets container job script"
  ([job]
   (:script job))
  ([job script]
   (try-unwrap job assoc :script script)))

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

(defn size
  "Gets or sets the resource size of the container job.  Size 1 means 1 cpu,
   2 GB, size 2 doubles that, etc..."
  ([job]
   (:size job))
  ([job s]
   (try-unwrap job assoc :size s)))

(defn blocked
  "Gets or sets job blocked status.  A blocked job will be marked as `blocked` once it
   is eligible for execution.  An explicit unblock action is required to execute it."
  [job]
  (assoc job :blocked true))

(defn unblocked
  "Unmarks a previously blocked job as unblocked.  Note that this has no effect if the
   job lifecycle status is already blocked, it only affects the job `blocked` status 
   before the script is started."
  [job]
  (dissoc job :blocked))

(def blocked?
  "Checks if given job is marked as blocked"
  (comp true? :blocked))

(defn artifact
  "Defines a new artifact with given id, located at given `path`.  This can be passed
   to `save-artifacts` or `restore-artifacts`."
  [id path]
  {:id id
   :path path})

(defn dir-artifact
  "Converts artifact that points to a file, to one that points to its parent
   directory.  If there is no parent directory, uses the current directory."
  [art]
  (update art :path (comp str #(or % ".") fs/parent)))

(defn save-artifacts
  "Configures the artifacts to save on a job."
  [job & arts]
  (try-unwrap job update :save-artifacts concat (flatten arts)))

(defn restore-artifacts
  "Configures the artifacts to restore on a job."
  [job & arts]
  (try-unwrap job update :restore-artifacts concat (flatten arts)))

(def cache
  "Defines a cache with given id an path, that can be passed to `caches`"
  artifact)

(defn caches
  "Configures the caches a job uses."
  [job & arts]
  (try-unwrap job update :caches concat (flatten arts)))

(defn- file-test [tester]
  (fn test-fn
    ([ctx pred]
     (some? (tester [ctx pred])))
    ([pred]
     (fn [ctx]
       (test-fn ctx pred)))))

(def files-added
  "List of files that have been added for this build"
  bc/files-added)

(def files-modified
  "List of modified files for this build"
  bc/files-modified)

(def files-removed
  "List of removed files for this build"
  bc/files-removed)

(def added?
  "Checks if files have been added in this build using the given predicate, which can either be a
   regex, a string or a matcher function.  Accepts one or two arguments: when given one argument,
   returns a function that accepts the context to check if the predicate matches.  When given two
   arguments, accepts the context and the predicate."
  (file-test bc/added?))

(def removed?
  "Checks if files have been removed in this build using the given predicate, which can either be a
   regex, a string or a matcher function.  Similar to `added?`."
  (file-test bc/removed?))

(def modified?
  "Checks if files have been modified in this build using the given predicate, which can either be a
   regex, a string or a matcher function.  Similar to `added?`."
  (file-test bc/modified?))

(def touched?
  "Checks if files have been touched in this build using the given predicate, which can either be a
   regex, a string or a matcher function.  Similar to `added?`."
  (file-test (partial apply bc/touched?)))

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
  bc/main-branch?)

(def build-params
  "Retrieves parameters this build has access to"
  api/build-params)

(def params
  "Shorthand for `build-params`"
  build-params)

(defn bash
  "Creates an action job that executes given commands in a bash script" 
  [id & cmds]
  (action-job id (apply bs/bash cmds)))

(def shell "Same as `bash`" bash)

(def build-id
  "Returns current build id"
  (comp :build-id :build))

(def checkout-dir
  "Returns the build checkout directory"
  (comp b/checkout-dir :build))

(def in-work
  "Given a relative path `p`, returns it as a subpath to the job working directory.
   Fails if an absolute path is given."
  bs/in-work)

(def git-ref
  "The git ref that triggered the build"
  bc/git-ref)

(def ref-regex
  "Applies the given regex on the ref from the context, returns the matching groups."
  bc/ref-regex)

(def archs
  "Retrieves list of available container architectures from context"
  :archs)

(def source
  "Returns the trigger source (e.g. `api`)"
  bc/trigger-src)

(def api-trigger?
  "True if the build has been triggered via api (i.e. typically manually by a human)"
  (comp (partial = :api) source))

(def manual?
  "Alias for `api-trigger?`"
  api-trigger?)
