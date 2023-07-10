(ns monkey.ci.build.shell
  (:require [babashka.process :as bp]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as core]))

(defn bash [& args]
  (fn [ctx]
    (log/debug "Executing shell script with args" args)
    (try
      (assoc core/success
             :output (:out (apply bp/shell {:out :string} args)))
      (catch Exception ex
        ;; Report the error
        (assoc core/failure
               :error ex)))))
