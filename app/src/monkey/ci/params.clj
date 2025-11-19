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
    (log/debug "Fetching build params from global api using url:" (:url config))
    (log/debug "Build sid:" (select-keys build [:org-id :repo-id]))
    (-> (select-keys config [:url])
        (ba/api-request (ba/as-edn
                         {:path (format "/org/%s/repo/%s/param"
                                        (:org-id build)
                                        (:repo-id build))
                          :headers {"authorization" (str "Token " (:token config))}
                          :method :get}))
        (md/chain :body))))

(defrecord MultiBuildParams [sources]
  p/BuildParams
  (get-build-params [_ b]
    (md/chain
     (->> sources
          (map #(p/get-build-params % b))
          (apply md/zip))
     (partial apply concat))))
