(ns build
  (:require [monkey.ci.api :as api]))

(defn ^:job check-build-params [ctx]
  (println "Fetching build parameters")
  (if (some? (api/build-params ctx))
    api/success
    api/failure))

[check-build-params]
