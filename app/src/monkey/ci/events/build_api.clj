(ns monkey.ci.events.build-api
  "Events implementation that uses the build api to send events"
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [martian.core :as mc]
            [monkey.ci.protocols :as p]
            [monkey.ci.build.api :as api]))

(defrecord BuildApiEventPoster [client]
  p/EventPoster
  (post-events [this evts]
    (let [e (cond-> evts
              (not (sequential? evts)) vector)]
      @(md/chain
        (mc/response-for client :post-events {:body e})
        (fn [{:keys [status] :as resp}]
          (when (or (nil? status) (>= status 400))
            (log/warn "Unable to post event" resp)))
        (constantly this)))))

(defn make-event-poster [client]
  (->BuildApiEventPoster client))

;; TODO Event receiving
