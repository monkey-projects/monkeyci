(ns monkey.ci.metrics
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [medley.core :as mc]
            [monkey.ci.prometheus :as prom]
            [taoensso.telemere :as t]))

(defn make-registry []
  (prom/make-registry))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (prom/scrape r))

(defn- count-listeners
  "Counts event state listeners for metrics"
  [state]
  (->> state
       :listeners
       vals
       (mapcat vals)
       (distinct)
       (count)))

#_(defn add-events-metrics [r events]
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

;; TODO Instrument prometheus registry
#_(extend-type io.resonant.micrometer.Registry
    co/Lifecycle
    (start [this]
      (add-events-metrics this (:events this)))

    (stop [this]
      (.close this)
      this))

(defn signal->counter
  "Registers a signal handler that creates a counter in the registry that counts 
   how many times a signal was received.  If `tags` is a function, it will be 
   invoked first without arguments to determine the tag names, and then with
   each received signal in order to get the tag values for the counter."
  [handler-id reg counter-id {:keys [opts tags tx]}]
  (letfn [(lbl-names []
            (when tags
              (map name (if (fn? tags)
                          (tags)
                          (keys tags)))))
          (lbl-vals [s]
            (when tags
              (if (fn? tags)
                (tags s)
                tags)))]
    (let [opts (mc/assoc-some opts :labels (lbl-names))
          counter (prom/make-counter counter-id reg opts)]
      (t/add-handler!
       handler-id
       (fn
         ([signal]
          (when-let [r (if tx
                         (some-> (eduction tx [signal]) first)
                         signal)]
            (prom/counter-inc counter 1 (lbl-vals signal))))
         ([])))
      counter)))

