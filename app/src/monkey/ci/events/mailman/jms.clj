(ns monkey.ci.events.mailman.jms
  "JMS-specific functionality for handling events.  This has mainly to do with
   queue and topic configurations and listeners."
  (:require [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.protocols :as p]
            [monkey.mailman
             [core :as mmc]
             [jms :as mj]]))

(def topic-prefix "topic://")

(def destination-types
  {"%s.builds"
   [:build/triggered :build/pending :build/initializing :build/start :build/end :build/canceled]
   "%s.scripts"
   [:script/initializing :script/start :script/end]
   "%s.jobs"
   [:job/pending :job/queued :job/initializing :job/start :job/end :job/skipped :job/executed
    :job/blocked :job/unblocked]
   "%s.jobs.containers"
   [:container/pending :container/initializing :container/start :container/script-end
    :container/end :sidecar/start :sidecar/end]
   "%s.jobs.commands"
   [:command/start :command/end]
   ;; All things that need to be run in a container go here.
   "%s.containers"
   [:build/queued :container/job-queued]
   ;; Topics for oci and k8s container tasks
   "%s.tasks.oci"
   [:oci/build-scheduled :oci/job-scheduled]
   "%s.tasks.k8s"
   [:k8s/build-scheduled :k8s/job-scheduled]
   ;; Consolidated build events
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

(defrecord JmsComponent [broker]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (let [dests (topic-destinations config)
          broker (mj/jms-broker (assoc config
                                       ;; We can specify a serializer that adds sid here
                                       ;;:serializer custom-serializer
                                       :destination-mapper (comp dests :type)))]
      (-> this
          (dissoc :config)           ; no longer needed
          (assoc :broker broker
                 :destinations dests))))

  (stop [this]
    (when broker
      (.close broker)
      (mj/disconnect broker))
    (assoc this :broker nil))

  p/AddRouter
  (add-router [{:keys [destinations]} routes opts]
    (let [router (mmc/router routes opts)]
      ;; TODO Add listeners for each destination referred to by route event types
      ;; but split up the routes so only those for the destination are added
      ;; TODO Allow specifying an sid selector for efficiency
      (->> routes
           (map first)
           (map (or (:destinations opts) destinations))
           (distinct)
           (map (partial hash-map :handler router :destination))
           (map (partial mmc/add-listener broker))
           (doall)))))
