(ns monkey.ci.containers.build-api
  "Container runner that invokes an endpoint on the build api.  This is meant to
   be used by child processes that do not have full infra permissions."
  (:require [manifold.deferred :as md]
            [monkey.ci
             [containers :as mcc]
             [edn :as edn]
             [protocols :as p]]))

(defrecord BuildApiContainerRunner [client]
  p/ContainerRunner
  (run-container [this job]
    (let [r (md/deferred)]
      (md/catch
        (client {:request-method :post
                 :path "/container"
                 :body (edn/->edn job)
                 :headers {"content-type" "application/edn"}})
        (fn [ex]
          (md/error! r ex)))
      ;; TODO Listen to events to realize deferred
      r)))
