(ns monkey.ci.web.common
  (:require [clojure.core.async :refer [go >!]]
            [monkey.ci.events :as e]))

(defn- from-context [req obj]
  (get-in req [:reitit.core/match :data :monkey.ci.web.handler/context obj]))

(defn req->bus
  "Gets the event bus from the request data"
  [req]
  (from-context req :event-bus))

(defn req->storage
  "Retrieves storage object from the request context"
  [req]
  (from-context req :storage))

(defn post-event
  "Posts event to the bus found in the request data.  Returns an async channel
   holding `true` if the event is posted."
  [req evt]
  (go (some-> (req->bus req)
              (e/channel)
              (>! evt))))

