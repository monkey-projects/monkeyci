(require '[monkey.ci.build.api :as api])
(require '[monkey.ci.build.core :as c])

(defn check-build-params [ctx]
  (println "Fetching build parameters")
  (if (some? (api/build-params ctx))
    c/success
    c/failure))

[(c/pipeline {:name "verify api"
              :steps [check-build-params]})]
