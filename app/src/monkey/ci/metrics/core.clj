(ns monkey.ci.metrics.core
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.metrics.prometheus :as prom]
            [taoensso.telemere :as t]))

(defn make-registry []
  (prom/make-registry))

(defn scrape
  "Creates a string that can be used by Prometheus for scraping"
  [r]
  (prom/scrape r))

(defn counter-id [parts]
  (->> parts
       (map name)
       (cs/join "_")
       (str "monkeyci_")))

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
    (signal->counter ::oci-calls reg "monkeyci_oci_calls"
                     {:description "Number of calls to OCI API endpoints"
                      :tags tags
                      :tx (id-filter :oci/invocation)})
    reg))

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
    (assoc this :registry (-> (make-registry)
                              (add-oci-metrics))))

  (stop [this]
    (remove-signal-handlers)
    this))

(def make-metrics ->Metrics)
