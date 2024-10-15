(ns monkey.ci.metrics
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [io.resonant.micrometer :as mm]
            [manifold.stream :as ms]
            [taoensso.telemere :as t]))

(defn make-registry []
  (mm/meter-registry {:type :prometheus}))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (some-> r :registry (.scrape)))

(defn- count-listeners
  "Counts event state listeners for metrics"
  [state]
  (->> state
       :listeners
       vals
       (mapcat vals)
       (distinct)
       (count)))

(defn add-events-metrics [r events]
  (when-let [ss (get-in events [:server :state-stream])]
    (let [state (atom nil)]
      ;; Constantly store the latest state, so it can be used by the gauges
      (ms/consume (partial reset! state) ss)
      (mm/get-gauge r "monkey_event_filters" {}
                    {:description "Number of different registered event filters"}
                    #(count (keys (:listeners @state))))
      (mm/get-gauge r "monkey_event_clients" {}
                    {:description "Total number of registered clients"}
                    #(count-listeners @state))))
  r)

(extend-type io.resonant.micrometer.Registry
  co/Lifecycle
  (start [this]
    (add-events-metrics this (:events this)))

  (stop [this]
    (.close this)
    this))

(defn signal->counter
  "Registers a signal handler that creates a counter in the registry that counts 
   how many times a signal was received.  If `tags` is a function, it  will be 
   invoked with the received signal in order to get the tags for the counter."
  [handler-id reg counter-id {:keys [opts tags tx]}]
  (letfn [(get-tags [s]
            (if (fn? tags)
              (tags s)
              tags))]
    (t/add-handler!
     handler-id
     (fn
       ([signal]
        (when-let [r (if tx
                       (some-> (eduction tx [signal]) first)
                       signal)]
          (mm/add-counter reg counter-id (get-tags r) opts 1)))
       ([])))))

