(ns monkey.ci.events.split
  "Events implementation that splits input and output.  Events are read from one
   side, and posted to another."
  (:require [monkey.ci.protocols :as p]))

(defrecord SplitEvents [in out]
  p/EventReceiver
  (add-listener [this ef l]
    (p/add-listener in ef l))
  (remove-listener [this ef l]
    (p/remove-listener in ef l))

  p/EventPoster
  (post-events [this evt]
    (p/post-events out evt)))
