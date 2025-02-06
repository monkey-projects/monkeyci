(ns monkey.ci.metrics
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [medley.core :as mc]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.prometheus :as prom]
            [taoensso.telemere :as t]))

(defn make-registry []
  (prom/make-registry))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (prom/scrape r))

;; (defn- count-listeners
;;   "Counts event state listeners for metrics"
;;   [state]
;;   (->> state
;;        :listeners
;;        vals
;;        (mapcat vals)
;;        (distinct)
;;        (count)))

;; (defn add-events-metrics
;;   "When the events object exposes a state stream, registers some metrics with the given
;;    registry.  Returns the updated registry."
;;   [r events]
;;     (when-let [ss (get-in events [:server :state-stream])]
;;       (let [state (atom nil)]
;;         ;; Constantly store the latest state, so it can be used by the gauges
;;         (ms/consume (partial reset! state) ss)
;;         (mm/get-gauge r "monkey_event_filters" {}
;;                       {:description "Number of different registered event filters"}
;;                       #(count (keys (:listeners @state))))
;;         (mm/get-gauge r "monkey_event_clients" {}
;;                       {:description "Total number of registered clients"}
;;                       #(count-listeners @state))))
;;     r)

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
            (log/trace "Increasing counter for signal:" r)
            (prom/counter-inc counter 1 (lbl-vals r))))
         ([])))
      counter)))

(defn- id-filter [id]
  (filter (cp/prop-pred :id id)))

(defn- add-oci-metrics [reg]
  (letfn [(tags
            ([]
             [:kind])
            ([signal]
             [(name (get-in signal [:data :kind]))]))]
    (signal->counter ::oci-calls reg "monkey_oci_calls"
                     {:description "Number of calls to OCI API endpoints"
                      :tags tags
                      :tx (id-filter :oci/invocation)})
    reg))

(defn- add-build-metrics [reg]
  (signal->counter :build/triggered reg "monkey_builds_triggered"
                   {:description "Number of triggered builds"
                    :tx (id-filter :build/triggered)})
  (signal->counter :build/started reg "monkey_builds_started"
                   {:description "Number of started builds"
                    :tx (id-filter :build/started)})
  (signal->counter :build/completed reg "monkey_builds_completed"
                   ;; TODO Include build result as tag
                   {:description "Number of completed builds"
                    :tx (id-filter :build/completed)})
  reg)

(defn- remove-signal-handlers []
  (let [handlers [::oci-calls
                  :build/triggered
                  :build/started
                  :build/completed]]
    (doseq [h handlers]
      (t/remove-handler! h))))

(defrecord Metrics []
  co/Lifecycle
  (start [this]
    ;; TODO Add build labels if present
    (assoc this :registry (-> (make-registry)
                              (add-oci-metrics)
                              (add-build-metrics))))

  (stop [this]
    (remove-signal-handlers)
    this))

(def make-metrics ->Metrics)
