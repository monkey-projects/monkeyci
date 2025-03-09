(ns monkey.ci.events.mailman.jms
  "JMS-specific functionality for handling events.  This has mainly to do with
   queue and topic configurations and listeners."
  (:require [medley.core :as mc]))

(def topic-prefix "topic://")

(def destination-types
  {"%s.builds"
   [:build/triggered :build/pending :build/initializing :build/start :build/end :build/canceled]
   "%s.scripts"
   [:script/initializing :script/start :script/end]
   "%s.jobs"
   [:job/pending :job/initializing :job/start :job/end :job/skipped :job/executed]
   "%s.jobs.containers"
   [:container/pending :container/initializing :container/start :container/end :sidecar/start :sidecar/end]
   "%s.jobs.commands"
   [:command/start :command/end]
   ;; All things that need to be run in a container go here.
   "%s.containers"
   [:build/queued :job/queued]
   "%s.build.updates"
   [:build/updated]})

(defn- make-dest [prefix dest]
  (format dest prefix))

(defn- types-to-destinations [dest-types make-dest]
  (->> dest-types
       (mapcat (fn [[fmt types]]
                 (map #(vector % fmt) types)))
       (into {})
       (mc/map-vals make-dest)))

(defn topic-destinations [conf]
  "Maps event types to topic destinations.  This is used by the mapper for outgoing events.
   A prefix is applied according to configuration."
  (types-to-destinations destination-types
                         (comp (partial str topic-prefix)
                               (partial make-dest (:prefix conf)))))

(def ^:deprecated event-destinations topic-destinations)

(defn queue-destinations
  "Similar to `topic-destinations`, but specifies a unique queue for each destination.
   This allows consumers to read from that queue only, ensuring each event is only
   processed once."
  [{:keys [prefix suffix]
    :or {suffix ".q"}}]
  (types-to-destinations destination-types
                         (fn [dest]
                           (let [f (make-dest prefix dest)]
                             (str topic-prefix f "::" f suffix)))))
