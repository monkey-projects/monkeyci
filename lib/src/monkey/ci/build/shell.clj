(ns monkey.ci.build.shell
  (:require [babashka.process :as bp]
            [clojure.tools.logging :as log]
            [monkey.ci.build.core :as core]))

(defn bash [& args]
  (fn [ctx]
    (let [work-dir (or (get-in ctx [:step :work-dir]) (:work-dir ctx))]
      (log/debug "Executing shell script with args" args "in work dir" work-dir)
      (try
        (let [opts (cond-> {:out :string
                            :err :string}
                     ;; Add work dir if specified in the context
                     work-dir (assoc :dir work-dir))]
          (assoc core/success
                 :output (:out (apply bp/shell opts args))))
        (catch Exception ex
          (let [{:keys [out err]} (ex-data ex)]
            ;; Report the error
            (assoc core/failure
                   :output out
                   :error err
                   :exception ex)))))))

(def home (System/getProperty "user.home"))
