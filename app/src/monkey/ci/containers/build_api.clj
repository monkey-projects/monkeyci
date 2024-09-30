(ns monkey.ci.containers.build-api
  "Container runner that invokes an endpoint on the build api.  This is meant to
   be used by child processes that do not have full infra permissions."
  (:require [clojure.tools.logging :as log]
            [manifold
             [bus :as mb]
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [edn :as edn]
             [jobs :as j]
             [protocols :as p]]))

(defn check-http-error [msg job {:keys [status body] :as resp}]
  (if (or (nil? status) (>= status 400))
    (throw (ex-info msg
                    {:job job
                     :cause resp}))
    body))

(defn- wait-for-job-executed
  "Listens to events on the build api server that indicate the container job
   has executed.  Returns a deferred that yields the container result when 
   it has finished, or a timeout exception when the job takes too long to
   complete."
  [bus job-id]
  (log/debug "Listening to events for container job to end for job" job-id)
  (let [src (mb/subscribe bus :job/executed)]
    (-> (->> src
             (ms/filter (comp (partial = job-id) :job-id))
             (ms/sliding-stream 10))
        (ms/take!)
        (md/timeout! j/max-job-timeout)
        (md/chain :result)
        (md/finally
          (fn []
            (log/debug "Unsubscribing from bus")
            (ms/close! src))))))

(defrecord BuildApiContainerRunner [client bus]
  p/ContainerRunner
  (run-container [this job]
    (log/info "Starting container job using build API:" (j/job-id job))
    (-> (md/zip
         (wait-for-job-executed (:bus bus) (j/job-id job))
         (-> (client {:request-method :post
                      :path "/container"
                      :body (edn/->edn {:job (j/as-serializable job)})
                      :headers {"content-type" "application/edn"}})
             (md/chain
              (partial check-http-error "Request to start container job failed" job))
             (md/catch (fn [ex]
                         (throw (ex-info "Failed to run container job using build API"
                                         {:cause ex
                                          :job job}))))))
        ;; Just return the job result
        (md/chain first))))
