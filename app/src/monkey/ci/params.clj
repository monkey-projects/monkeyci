(ns monkey.ci.params
  "Build parameter related functionality"
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.build
             [api :as ba]
             [api-server :as bas]]
            [monkey.ci.protocols :as p]))

(defrecord ApiBuildParams [api-maker]
  p/BuildParams
  (get-build-params [this build]
    (bas/get-params-from-api (api-maker build) build)))

(defrecord FixedBuildParams [params]
  p/BuildParams
  (get-build-params [_ _]
    (log/debug "Returning fixed build params:" (map :name params))
    (md/success-deferred params)))

(defrecord CliBuildParams [config]
  p/BuildParams
  (get-build-params [this build]
    (-> {:url (:api config)}
        (ba/api-request (ba/as-edn
                         {:path (format "/org/%s/repo/%s/param"
                                        (:org-id build)
                                        (:repo-id build))
                          :headers {"authorization" (str "Token " (:api-key config))}
                          :method :get}))
        (md/chain :body))))
