(ns monkey.ci.script.core
  (:require [babashka.fs :as fs]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clj-kondo.core :as clj-kondo]
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
  ["yaml" "yml" "json" "edn" "clj"])

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

(defn- verify-clj [dir]
  (let [{:keys [summary] :as r} (clj-kondo/run! {:lint [dir]})]
    {:details r
     :type :clj
     :result (cond
               (pos? (:error summary)) :errors
               (pos? (:warning summary)) :warnings
               :else :success)}))

(defn- try-parse [parser type ext dir]
  (let [f (fs/file dir (str "build." ext))]
    (when (fs/exists? f)
      (-> (try
            (if (some? (parser f))
              {:result :success}
              {:result :warnings
               :details {:warnings ["File contents is empty"]}})
            (catch Exception ex
              {:result :errors
               :details {:errors [(ex-message ex)]}}))
          (assoc :type type)))))

(defn- verify-edn [dir]
  (try-parse edn/edn-> :edn "edn" dir))

(defn- verify-json [dir]
  (try-parse (comp json/parse-string slurp) :json "json" dir))

(defn- verify-yaml [ext dir]
  (try-parse load-yaml :yaml ext dir))

(def verifiers
  {"clj"  verify-clj
   "edn"  verify-edn
   "json" verify-json
   "yaml" (partial verify-yaml "yaml")
   "yml"  (partial verify-yaml "yml")})

(defn verify
  "Verifies all scripts found in the directory.  For clj, it runs a linter, for other
   types it tries to parse them.  Returns verification results per file type."
  [dir]
  (->> extensions
       (map (fn [ext]
              (when-let [v (get verifiers ext)]
                (v dir))))
       (filter some?)))
