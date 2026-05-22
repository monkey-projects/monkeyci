(ns monkey.ci.cli.verify
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-kondo.core :as clj-kondo]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]))

(defn- verify-clj [dir]
  (when (fs/exists? (fs/path dir "build.clj"))
    (let [{:keys [summary] :as r} (clj-kondo/run! {:lint [dir]})]
      {:details r
       :type :clj
       :result (cond
                 (pos? (:error summary)) :errors
                 (pos? (:warning summary)) :warnings
                 :else :success)})))

(defn- try-parse [parser type ext dir]
  (let [f (fs/file dir (str "build." ext))]
    (when (fs/exists? f)
      (-> (try
            (if-let [r (not-empty (parser f))]
              {:result :success
               :details r}
              {:result :warnings
               :details {:warnings ["File contents is empty"]}})
            (catch Exception ex
              {:result :errors
               :details {:errors [(ex-message ex)]}}))
          (assoc :type type
                 :file (str f))))))

(defn- verify-edn [dir]
  (try-parse (comp edn/read-string slurp) :edn "edn" dir))

(defn- verify-json [dir]
  (try-parse (comp json/parse-string slurp) :json "json" dir))

(defn- verify-yaml [ext dir]
  (try-parse (comp yaml/parse-string slurp) :yaml ext dir))

(def verifiers
  {"clj"  verify-clj
   ;; TODO For non-clj, check if the jobs match spec
   "edn"  verify-edn
   "json" verify-json
   "yaml" (partial verify-yaml "yaml")
   "yml"  (partial verify-yaml "yml")})

(defn verify
  "Verifies all scripts found in the directory.  For clj, it runs a linter, for other
   types it tries to parse them.  Returns verification results per file type."
  [dir]
  (->> (keys verifiers)
       (map (fn [ext]
              (when-let [v (get verifiers ext)]
                (v dir))))
       (filter some?)))
