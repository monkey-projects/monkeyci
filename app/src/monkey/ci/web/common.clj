(ns monkey.ci.web.common
  (:require [clojure.core.async :refer [go >!]]
            [monkey.ci.events :as e]))

(defn post-event
  "Posts event to the bus found in the request data.  Returns an async channel
   holding `true` if the event is posted."
  [req evt]
  (go (some-> (get-in req [:reitit.core/match :data :monkey.ci.web.handler/context :bus])
              (e/channel)
              (>! evt))))

