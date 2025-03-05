(ns monkey.ci.script
  (:require
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [monkey.ci.build :as build]
   [monkey.ci.build.core :as bc]
   [monkey.ci.jobs :as j]))

;;; Script loading

(defn- load-script
  "Loads the jobs from the build script, by reading the script files 
   dynamically.  If the build script does not define its own namespace,
   one will be randomly generated to avoid collisions."
  [dir build-id]
  ;; Don't wrap in ns, since `in-ns` may throw an exception at runtime if it
  ;; can't rebind `*ns*`
  (let [path (io/file dir "build.clj")]
    (log/debug "Loading script:" path)
    ;; This should return jobs to run
    (load-file (str path)))
  #_(let [tmp-ns (symbol (or build-id (str "build-" (random-uuid))))]
      ;; Declare a temporary namespace to load the file in, in case
      ;; it does not declare an ns of it's own.
      (in-ns tmp-ns)
      #_(clojure.core/use 'clojure.core)
      (try
        (let [path (io/file dir "build.clj")]
          (log/debug "Loading script:" path)
          ;; This should return jobs to run
          (load-file (str path)))
        (finally
          ;; Return
          (in-ns 'monkey.ci.script)
          (remove-ns tmp-ns)))))

(defn- assign-ids
  "Assigns an id to each job that does not have one already."
  [jobs]
  (letfn [(assign-id [x id]
            (if (nil? (bc/job-id x))
              (assoc x :id id)
              x))]
    ;; TODO Sanitize existing ids
    (map-indexed (fn [i job]
                   (assign-id job (format "job-%d" (inc i))))
                 jobs)))

(defn resolve-jobs
  "The build script either returns a list of pipelines, a set of jobs or a function 
   that returns either.  This function resolves the jobs by processing the script
   return value."
  [p rt]
  (-> (j/resolve-jobs p rt)
      ;; TODO Wrap errors and extensions here?
      (assign-ids)))

(defn load-jobs
  "Loads the script and resolves the jobs"
  [build rt]
  (-> (load-script (build/script-dir build) (build/build-id build))
      (resolve-jobs rt)))
