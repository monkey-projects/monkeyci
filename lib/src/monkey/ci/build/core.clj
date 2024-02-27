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
  (= :failure status))

(defn skipped? [{:keys [status]}]
  (= :skipped status))

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

(defn map->job
  "Converts legacy map jobs into job records"
  [m]
  (cond
    (some? (:action m)) (map->ActionJob m)
    (some? (:container/image m)) (map->ContainerJob (assoc m :image (:container/image m)))
    :else m))

(defrecord Pipeline [jobs name])

(defn- add-dependencies [jobs]
  (reduce (fn [r j]
            (conj r (cond-> j
                      (not-empty r)
                      (update :dependencies (comp vec distinct conj) (:id (last r))))))
          []
          jobs))

(defn job-id [x]
  (or (:id x) (:job/id (meta x))))

(defn- assign-ids [jobs]
  (letfn [(assign-id [x id]
            (if (nil? (job-id x))
              (if (map? x)
                (assoc x :id id)
                (with-meta x (assoc (meta x) :job/id id)))
              x))]
    (map-indexed (fn [i {:keys [id] :as j}]
                   (cond-> j
                     (nil? id) (assign-id (keyword (format "job-%d" (inc i))))))
                 jobs)))

(defn pipeline
  "Create a pipeline with given config"
  [config]
  {:pre [(s/valid? :ci/pipeline config)]}
  (-> config
      ;; Convert steps into jobs, backwards compatibility
      (mc/assoc-some :jobs (:steps config))
      (dissoc :steps)
      (update :jobs (comp add-dependencies
                          assign-ids
                          (partial map map->job)))
      (map->Pipeline)))

(defmacro defpipeline
  "Convenience macro that declares a var for a pipeline with the given name 
   with specified jobs"
  [n jobs]
  `(def ~n
     (pipeline
      {:name ~(name n)
       :jobs ~jobs})))

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
