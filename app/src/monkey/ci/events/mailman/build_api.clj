(ns monkey.ci.events.mailman.build-api
  "Mailman implementation that uses the build api for event posting and
   receiving.  This is used by the build script."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.build.api :as api]
            [monkey.ci.events.mailman :as em]
            [monkey.mailman
             [core :as mmc]
             [manifold :as mmm]
             [utils :as mmu]]))

;; The broker implementation posts directly to the build api using the HTTP
;; client.  Receiving however is done using manifold streams: when a listener
;; is first added, it opens an SSE stream that receives all events from the
;; remote api server.  These events are piped to the registered listeners.

(deftype Listener [id handler stream listeners]
  mmc/Listener
  (invoke-listener [this evt]
    (handler evt))
  (unregister-listener [this]
    (swap! listeners dissoc id)
    (ms/close! stream)
    true))

(defn- reposting-handler [l broker]
  (fn [evt]
    (mmu/invoke-and-repost evt broker [l])))

(defn- post-events [client evts]
  (client (api/as-edn {:method :post
                       :path "/events"
                       :body (pr-str evts)
                       :content-type :edn
                       :throw-exceptions false})))

(defrecord BuildApiBroker [api-client event-stream listeners]
  mmc/EventPoster
  (post-events [this evts]
    (when-not (empty? evts)
      (log/trace "Posting events to build api:" evts)
      @(md/chain
        (post-events api-client evts)
        (fn [{:keys [status] :as res}]
          (if (and status (< status 400))
            evts
            (log/warn "Unable to post events to build api" res))))))
  
  mmc/EventReceiver
  ;; Only listeners are supported, no polling
  (add-listener [this h]
    (let [s (ms/stream)
          id (random-uuid)
          l (->Listener id h s listeners)]
      (log/debug "Adding listener for build api events:" id)
      (swap! listeners assoc id l)
      ;; Connect the listener stream and consume it
      (ms/connect event-stream s)
      (md/finally
        (ms/consume (reposting-handler l this) s)
        (fn []
          (log/debug "Listener shut down:" id)))
      l)))

(defn make-broker [api-client event-stream]
  (->BuildApiBroker api-client event-stream (atom {})))

(defrecord BuildApiBrokerComponent [api-client event-stream broker]
  co/Lifecycle
  (start [this]
    (assoc this :broker (make-broker api-client (:stream event-stream))))

  (stop [this]
    this)

  em/AddRouter
  (add-router [this routes opts]
    (mmc/add-listener (:broker this) (mmc/router routes opts))))
