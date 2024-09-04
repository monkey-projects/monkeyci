(ns monkey.ci.containers.build-api
  "Container runner that invokes an endpoint on the build api.  This is meant to
   be used by child processes that do not have full infra permissions."
  (:require [clj-commons.byte-streams :as bs]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [containers :as mcc]
             [edn :as edn]
             [jobs :as j]
             [protocols :as p]]
            [monkey.ci.events.http :as eh]))

(defrecord BuildApiContainerRunner [client]
  p/ContainerRunner
  (run-container [this job]
    (let [r (md/deferred)]
      (-> (client {:request-method :post
                   :path "/container"
                   :body (edn/->edn {:job (j/as-serializable job)})
                   :headers {"content-type" "application/edn"}})
          ;; Listen to events to realize deferred
          (md/chain
           (fn [_]
             (client {:request-method :get
                      :path "/events"}))
           :body
           bs/to-line-seq
           ms/->source
           (partial ms/filter not-empty)
           (partial ms/map eh/parse-event-line)
           (fn [events]
             ;; TODO Make sure the stream is closed on success
             ;; TODO Set a timeout
             ;; TODO Refactor to an event listener, so we can use existing code
             (ms/consume (fn [{:keys [type job-id result]}]
                           (when (and (= :container-job/end type)
                                      (= job-id (j/job-id job)))
                             (md/success! r result)))
                         events)))
          (md/catch (fn [ex]
                      (md/error! r ex))))
      r)))
