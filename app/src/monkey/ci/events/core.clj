(ns monkey.ci.events.core)

(defprotocol EventPoster
  (post-event [poster evt]))

(defprotocol EventReceiver
  (add-listener [recv l])
  (remove-listener [recv l]))
