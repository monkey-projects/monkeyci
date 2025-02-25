(ns monkey.ci.events.mailman.build-api
  "Mailman implementation that uses the build api for event posting and
   receiving.  This is used by the build script."
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.events.build-api :as eba]
            [monkey.mailman
             [core :as mmc]
             [manifold :as mmm]]))

;; The broker implementation posts directly to the build api using the HTTP
;; client.  Receiving however is done using manifold streams: when a listener
;; is first added, it opens an SSE stream that receives all events from the
;; remote api server.  These events are piped to the registered listeners.

(defrecord Listener [id handler listeners]
  mmc/Listener
  (unregister-listener [this]
    (swap! listeners dissoc id)
    true))

(defrecord BuildApiBroker [api-client event-stream listeners]
  mmc/EventPoster
  (post-events [this evts]
    (log/trace "Posting events to build api:" evts)
    @(md/chain
      (eba/post-events api-client evts)
      (fn [{:keys [status] :as res}]
        (if (and status (< status 400))
          evts
          (log/warn "Unable to post events to build api" res)))))
  
  mmc/EventReceiver
  ;; Only listeners are supported, no polling
  (add-listener [this h]
    (let [l (->Listener (random-uuid) h listeners)]
      (log/debug "Adding listener for build api events:" (:id l))
      (swap! listeners assoc (:id l) l)
      (ms/consume h event-stream)
      l)))

(defn make-broker [api-client event-stream]
  (->BuildApiBroker api-client event-stream (atom {})))
