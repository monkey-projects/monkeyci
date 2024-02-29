(require '[monkey.ci.build.api :as api])
(require '[monkey.ci.build.core :as c])

(defn ^:job check-build-params [ctx]
  (println "Fetching build parameters")
  (if (some? (api/build-params ctx))
    c/success
    c/failure))

[check-build-params]
