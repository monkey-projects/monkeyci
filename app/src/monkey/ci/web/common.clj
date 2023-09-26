(ns monkey.ci.web.common
  (:require [clojure.core.async :refer [go >!]]
            [monkey.ci.events :as e]))

(defn req->bus
  "Gets the event bus from the request data"
  [req]
  (get-in req [:reitit.core/match :data :monkey.ci.web.handler/context :event-bus]))

(defn post-event
  "Posts event to the bus found in the request data.  Returns an async channel
   holding `true` if the event is posted."
  [req evt]
  (go (some-> (req->bus req)
              (e/channel)
              (>! evt))))

