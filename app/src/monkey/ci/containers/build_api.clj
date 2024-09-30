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

(defn- wait-for-job-executed
  "Listens to events on the build api server that indicate the container job
   has executed.  Returns a deferred that yields the container result when 
   it has finished, or a timeout exception when the job takes too long to
   complete."
  [client job]
  (let [evt-stream (promise)
        src (promise)
        job-id (j/job-id job)]
    (log/debug "Listening to events for container job to end for job" job-id)
    (-> (client {:request-method :get
                 :path "/events"})
        (md/chain
         (fn [{:keys [status body] :as resp}]
           (if (or (nil? status) (>= status 400))
             (throw (ex-info "Failed to listen to build API events"
                             {:job job
                              :cause resp}))
             body))
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
         (partial ms/filter
                  (fn [{:keys [type result] :as evt}]
                    (log/debug "Got event while waiting for container" job-id "to be executed:" evt)
                    (and (= :job/executed type)
                         (= job-id (:job-id evt)))))
         (fn [stream]
           (md/timeout! (ms/take! stream) j/max-job-timeout))
         (fn [{:keys [result]}]
           (log/debug "Container job" job-id "completed:" result)
           result))
        (md/finally
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

(defrecord BuildApiContainerRunner [client]
  p/ContainerRunner
  (run-container [this job]
    (log/info "Starting container job using build API:" (j/job-id job))
    (-> (md/zip
         (wait-for-job-executed client job)
         (-> (client {:request-method :post
                      :path "/container"
                      :body (edn/->edn {:job (j/as-serializable job)})
                      :headers {"content-type" "application/edn"}})
             (md/catch (fn [ex]
                         (throw (ex-info "Failed to run container job using build API"
                                         {:cause ex
                                          :job job}))))))
        ;; Just return the job result
        (md/chain first))))
