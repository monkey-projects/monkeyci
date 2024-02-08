(ns monkey.ci.events.legacy
  "Legacy event poster, using the async bus"
  (:require [monkey.ci.events :as le]
            [monkey.ci.events.core :as ec]))

(deftype LegacyEvents [bus]
  ec/EventPoster
  (post-events [this evt]
    (let [events (cond-> evt (not (sequential? evt)) vector)]
      (doseq [e events]
        (le/post-event bus e)))
    this))

(defmethod ec/make-events :legacy [{:keys [event-bus]}]
  (->LegacyEvents event-bus))
