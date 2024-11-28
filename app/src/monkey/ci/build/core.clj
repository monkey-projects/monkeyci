(ns monkey.ci.build.core
  "Core build script functionality.  This is used by build scripts to create
   the configuration which is then executed by the configured runner.  Which
   runner is configured or active depends on the configuration of the MonkeyCI
   application that executes the script."
  (:require [clojure.spec.alpha :as s]
            [medley.core :as mc]
            [monkey.ci.build.spec]))

(defn status [v]
  {:status v})

(def success (status :success))
(def failure (status :failure))
(def skipped (status :skipped))

(defn status?
  "Checks if the given object is a job status"
  [x]
  (some? (:status x)))

(defn success? [{:keys [status]}]
  (or (nil? status)
      (= :success status)))

(defn failed? [{:keys [status]}]
  (#{:failure :error} status))

(defn skipped? [{:keys [status]}]
  (= :skipped status))

(defn with-message [r msg]
  (assoc r :message msg))

;; Job that executes a function
(defrecord ActionJob [id status action])

(defn action-job
  "Creates a new job"
  ([id action opts]
   (map->ActionJob (merge opts {:id id :action action})))
  ([id action]
   (action-job id action {})))

(def action-job? (partial instance? ActionJob))

;; Job that runs in a container
(defrecord ContainerJob [id status image script])

(defn container-job
  "Creates a job that executes in a container"
  [id props]
  (map->ContainerJob (assoc props :id id)))

(def container-job? (partial instance? ContainerJob))

(defn step->job
  "Converts legacy map jobs into job records"
  [m]
  (cond
    (action-job? m) m
    (container-job? m) m
    (some? (:action m)) (map->ActionJob (mc/assoc-some m :id (:name m)))
    (some? (:container/image m)) (map->ContainerJob (assoc m :image (:container/image m)))
    (fn? m) m
    :else nil))

(defrecord Pipeline [jobs name])

(defn pipeline? [x]
  (instance? Pipeline x))

(defn job-id [x]
  (or (:id x) (:job/id (meta x))))

(defn pipeline
  "Create a pipeline with given config"
  [config]
  {:pre [(s/valid? :ci/pipeline config)]}
  (-> config
      ;; Convert steps into jobs, backwards compatibility
      (mc/assoc-some :jobs (:steps config))
      (dissoc :steps)
      (update :jobs (partial map step->job))
      (map->Pipeline)))

(defmacro defpipeline
  "Convenience macro that declares a var for a pipeline with the given name 
   with specified jobs"
  [n jobs]
  `(def ~n
     (pipeline
      {:name ~(name n)
       :jobs ~jobs})))

(defn as-job
  "Marks fn `f` as a job"
  [f]
  (with-meta f {:job true}))

(defmacro defjob
  "Defines an action job function"
  [n & args]
  `(def ~n (action-job ~(name n) (fn ~@args))))

(defn depends-on
  "Adds dependencies to the given job"
  [job ids]
  (update job :dependencies (comp vec distinct concat) ids))

(defn save-artifacts
  "Adds given artifacts to the job saved artifacts"
  [job artifacts]
  (update job :save-artifacts (comp vec concat) artifacts))

(defn restore-artifacts
  "Adds given artifacts to the job restored artifacts"
  [job artifacts]
  (update job :restore-artifacts (comp vec concat) artifacts))

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

(def work-dir "The job work dir" (comp :work-dir :job))

(def file-changes (comp :changes :build))

(def files-added
  "Files that have been added by the commits"
  (comp :added file-changes))

(def files-modified
  "Files that have been modified by the commits"
  (comp :modified file-changes))

(def files-removed
  "Files that have been removed by the commits"
  (comp :removed file-changes))

(def regex? (partial instance? java.util.regex.Pattern))

(defn ->pred [x]
  (cond
    (regex? x) (partial re-matches x)
    (fn? x) x
    :else (partial = x)))

(defn- matches-pred? [coll p]
  (some (->pred p) coll))

(defn- pred-matcher [coll]
  (fn [[rt p]]
    (some (->pred p) (coll rt))))

(def added?
  "Checks if any of the added files matches `p`.  If `p` is a function, it is used
   as a predicate.  If it's a string, an exact match is performed.  If it's a regex,
   a regex match is done."
  (pred-matcher files-added))

(def modified?
  "Similar to `added?` but for modified files"
  (pred-matcher files-modified))

(def removed?
  "Similar to `added?` but for removed files"
  (pred-matcher files-removed))

(def touched?
  "Returns `true` if the path occurs in any of the file changes.  If `p` is a regex, 
   checks if any of the files match the regex.  If `p` is a function, it is applied
   as a predicate against the files, until one matches."
  ;; Wrap the args in a vector because some-fn only passes one argument
  (comp (some-fn added? modified? removed?) vector))

(def build-id
  "Retrieves this build id from the context"
  (comp :build-id :build))

(def trigger-src
  "Returns build trigger source (api, github-app, etc..."
  (comp :source :build))
