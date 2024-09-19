(ns monkey.ci.containers.build-api
  "Container runner that invokes an endpoint on the build api.  This is meant to
   be used by child processes that do not have full infra permissions."
  (:require [clj-commons.byte-streams :as bs]
            [clojure.tools.logging :as log]
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
    (let [r (-> (md/deferred)
                (md/timeout! j/max-job-timeout))
          evt-stream (promise)
          src (promise)
          job-id (j/job-id job)]
      (-> (client {:request-method :post
                   :path "/container"
                   :body (edn/->edn {:job (j/as-serializable job)})
                   :headers {"content-type" "application/edn"}})
          ;; Listen to events to realize deferred
          (md/chain
           (fn [_]
             (log/debug "Listening to events for container job to end for job" (j/job-id job))
             (client {:request-method :get
                      :path "/events"}))
           :body
           (fn [is]
             ;; Store it so we can close it later
             (deliver evt-stream is)
             is)
           bs/to-line-seq
           ms/->source
           (fn [s]
             (deliver src s)
             s)
           (partial ms/filter not-empty)
           (partial ms/map eh/parse-event-line)
           (fn [events]
             ;; TODO Refactor to an event listener, so we can use existing code
             (ms/consume (fn [{:keys [type job] :as evt}]
                           (log/debug "Got event while waiting for container" job-id "to end:" evt)
                           (let [result (:result job)]
                             (if (and (= :job/end type)
                                      (= job-id (j/job-id job)))
                               (do
                                 (log/debug "Container job" job-id "completed:" result)
                                 (md/success! r result))
                               (log/debug "No match: same types?" (= :container-job/end type)
                                          ", same job id?" (= job-id (j/job-id job))))))
                         events)))
          (md/catch (fn [ex]
                      (md/error! r ex))))
      (md/finally
        r
        (fn []
          (log/debug "Closing event stream on client side")
          (if (realized? evt-stream)
            (.close @evt-stream)
            (log/warn "Unable to close event inputstream, not delivered yet."))
          ;; We need to close the stream explicitly, because it is not automatically
          ;; closed if the input stream is closed.
          (if (realized? src)
            (ms/close! @src)
            (log/warn "Unable to close source stream, not delivered yet."))
          (log/debug "Stream and sink closed"))))))
