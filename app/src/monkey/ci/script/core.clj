(ns monkey.ci.script.core
  (:require [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci
             [build :as build]
             [edn :as edn]
             [jobs :as j]
             [protocols :as p]]
            [monkey.ci.build.core :as bc]))

;;; Script loading

(def extensions
  "Accepted script extensions, in order of processing"
  ["yaml" "json" "edn" "clj"])

(defn- infer-type [job]
  ;; Currently, only container jobs are supported in non-clj files
  (assoc job :type :container))

(defn- load-edn [path]
  (->> (edn/edn-> path)
       (map infer-type)))

(defn- load-json [path]
  (with-open [r (io/reader path)]
    (->> (json/parse-stream r csk/->kebab-case-keyword)
         (map infer-type))))

;; Required for yaml
(extend-type flatland.ordered.map.OrderedMap
  p/JobResolvable
  (resolve-jobs [m rt]
    (when (j/job? m) [m])))

(defn- load-yaml [path]
  (-> (slurp path)
      (yaml/parse-string :key-fn (comp csk/->kebab-case-keyword :key))
      (as-> x (map infer-type x))))

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
  {"edn"  load-edn
   "json" load-json
   "yaml" load-yaml
   "yml"  load-yaml
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
  (log/debug "Resolving:" p)
  (-> (j/resolve-jobs p rt)
      ;; TODO Wrap errors and extensions here?
      (assign-ids)))

(defn load-jobs
  "Loads the script and resolves the jobs"
  [build rt]
  (-> (load-scripts (build/script-dir build))
      (resolve-jobs rt)))

