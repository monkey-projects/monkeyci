(ns monkey.ci.events.mailman.jms
  "JMS-specific functionality for handling events.  This has mainly to do with
   queue and topic configurations and listeners."
  (:require [medley.core :as mc]))

(defn make-dest [prefix fmt]
  (format fmt prefix))

(def ^:private destination-types
  {"queue://%s.builds"
   [:build/triggered :build/pending :build/initializing :build/start :build/end :build/canceled]
   "queue://%s.scripts"
   [:script/initializing :script/start :script/end]
   "queue://%s.jobs"
   [:job/pending :job/initializing :job/start :job/end :job/skipped :job/executed]
   "queue://%s.jobs.containers"
   [:container/pending :container/initializing :container/start :container/end :sidecar/start :sidecar/end]
   "queue://%s.jobs.commands"
   [:command/start :command/end]
   ;; All things that need to be run in a container go here
   "queue://%s.containers"
   [:build/queued :job/queued]
   "topic://%s.build.updates"
   [:build/updated]})

(defn event-destinations [conf]
  "Maps event types to destinations.  This is used by the mapper for outgoing events.
   A prefix is applied according to configuration."
  (->> destination-types
       (mapcat (fn [[fmt types]]
                 (map #(vector % fmt) types)))
       (into {})
       (mc/map-vals (partial make-dest (:prefix conf)))))
