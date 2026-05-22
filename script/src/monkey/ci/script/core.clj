(ns monkey.ci.script.core
  "Core script functionality"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.script
             [edn :as edn]
             [build :as build]
             [jobs :as j]
             [json :as json]
             [yaml :as yaml]]))

;;; Script loading

(def extensions
  "Accepted script extensions, in order of processing"
  ["yaml" "yml" "json" "edn" "clj"])

(defn- load-clj
  "Loads the jobs from the build script, by reading the script files dynamically."
  [path]
  ;; Don't wrap in ns, since `in-ns` may throw an exception at runtime if it
  ;; can't rebind `*ns*`
  (log/debug "Loading script:" path)
  ;; This should return jobs to run
  (load-file (str path)))

(def loaders
  "Script loader per extension"
  {"edn"  edn/load-edn
   "json" json/load-json
   "yaml" yaml/load-yaml
   "yml"  yaml/load-yaml
   "clj"  load-clj})

(defn- load-script
  "Loads the jobs defined in the script file, which could be declarative (yaml, 
   json or edn) or a clj source file."
  [dir ext]
  (let [f (fs/path dir (str "build." ext))
        l (get loaders ext)]
    (when (fs/exists? f)
      (l (fs/file f)))))

(defn- load-scripts [dir]
  (->> extensions
       (map (partial load-script dir))))

(defn- assign-ids
  "Assigns an id to each job that does not have one already."
  [jobs]
  (letfn [(assign-id [x id]
            (if (nil? (j/job-id x))
              (assoc x :id id)
              x))]
    ;; TODO Sanitize existing ids
    (map-indexed (fn [i job]
                   (assign-id job (format "job-%d" (inc i))))
                 jobs)))

(defn resolve-jobs
  "The build script must return something that satisfies `JobResolvable`.  
   This function resolves the jobs by processing the script return value
   and assigns a generic id to the jobs that don't have one yet."
  [p rt]
  (log/debug "Resolving:" p)
  (-> (j/resolve-jobs p rt)
      (assign-ids)))

(defn load-jobs
  "Loads the script and resolves the jobs"
  [build rt]
  (-> (load-scripts (build/script-dir build))
      (resolve-jobs rt)))
