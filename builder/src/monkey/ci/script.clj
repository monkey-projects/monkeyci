(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [dir]
  (log/info "Executing script at:" dir)
  (let [tmp-ns (symbol (str "build-" (random-uuid)))]
    ;; I don't think this is a very good approach
    (in-ns tmp-ns)
    (clojure.core/use 'clojure.core)
    (try
      (load-file (str (io/file dir "build.clj")))
      (catch Exception ex
        (println "Failed to execute script" ex))
      (finally
        ;; Return
        (in-ns 'monkey.ci.script)
        (remove-ns tmp-ns)))))
