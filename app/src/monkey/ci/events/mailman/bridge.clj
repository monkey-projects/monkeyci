(ns monkey.ci.events.mailman.bridge
  "Bridge event handlers.  Provided as a temporary compatibility layer between
   old-style event system and the newer mailman style.  In time, this will be
   removed as we fully migrate to mailman."
  (:require [com.stuartsierra.component :as co]
            [monkey.ci.protocols :as p]
            [monkey.mailman.core :as mcc]
            [monkey.mailman
             [core :as mcc]
             [interceptors :as mi]]))

(defrecord MailmanEventPoster [broker]
  ;; Compatibility event poster.  Used by old style code to post events that
  ;; get posted to the configured destinations.
  p/EventPoster
  (post-events [this evts]
    (mcc/post-events (:broker broker) (if (sequential? evts) evts [evts]))))

(def bridge-routes
  "Routes designed to receive events from the old-style queue, and re-post
   them as-is to the new queues."
  (-> [:build/pending :build/initializing :build/start :build/end :build/canceled
       :script/initializing :script/start :script/end
       :job/initializing :job/start :job/executed :job/end :job/skipped
       :container/start :container/end
       :command/start :command/end]
      (as-> t (mapv vector t (repeat [{:handler :event
                                       :interceptors [(mi/sanitize-result)]}])))))
