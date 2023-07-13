(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as bc]))

(defn run-pipelines []
  (let [p @bc/pipelines]
    (log/info "Found" (count p) "pipelines")))

(defn- load-pipelines [dir]
  (let [tmp-ns (symbol (str "build-" (random-uuid)))]
    ;; I don't think this is a very good approach
    (in-ns tmp-ns)
    (clojure.core/use 'clojure.core)
    (try
      (let [path (io/file dir "build.clj")]
        (log/info "Loading script:" path)
        ;; This should return pipelines to run
        (load-file (str path)))
      (catch Exception ex
        (log/error "Failed to execute script" ex)
        (assoc bc/failure :exception ex))
      (finally
        ;; Return
        (in-ns 'monkey.ci.script)
        (remove-ns tmp-ns)))))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [dir]
  (log/info "Executing script at:" dir)
  (let [p (load-pipelines dir)]
    (log/info "Loaded pipelines:" p)
    ;; TODO Actually run them
    {:status :success}))
