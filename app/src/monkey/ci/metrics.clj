(ns monkey.ci.metrics
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [io.resonant.micrometer :as mm]
            [manifold.stream :as ms]
            [monkey.ci.runtime :as rt]))

(defn make-registry []
  (mm/meter-registry {:type :prometheus}))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (some-> r :registry (.scrape)))

(defn- count-listeners [state]
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

(defmethod rt/setup-runtime :metrics [conf _]
  (cond-> (make-registry)
    (some? (:events conf)) (co/using [:events])))
