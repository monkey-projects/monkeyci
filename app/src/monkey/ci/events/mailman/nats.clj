(ns monkey.ci.events.mailman.nats
  "Configuration for NATS subjects"
  (:require [monkey.nats.core :as nats]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.events.mailman.jms :as jms]
            [monkey.ci.protocols :as p]
            [monkey.mailman.core :as mmc]
            [monkey.mailman.nats.core :as mnc]))

(def ^:private subject-types
  ;; Use the same as jms
  jms/destination-types)

(defn make-subject-mapping [prefix]
  (->> subject-types
       (mc/map-keys #(format % prefix))
       (reduce-kv (fn [r s types]
                    (->> types
                         (map #(vector % s))
                         (into r)))
                  {})))

(defn types-to-subjects [prefix]
  (make-subject-mapping prefix))

(defrecord NatsComponent [broker]
  co/Lifecycle
  (start [this]
    (log/debug "Connecting to NATS broker at" (get-in this [:config :urls]))
    (let [conf (:config this)
          conn (nats/make-connection conf)
          subjects (types-to-subjects (:prefix conf))
          broker-conf (-> conf
                          (select-keys [:stream :consumer :poll-opts])
                          (merge {:subject-mapper (comp subjects :type)}))]
      (log/debug "Using broker configuration:" broker-conf)
      (-> this
          (assoc :conn conn
                 :broker (mnc/make-broker conn broker-conf)
                 :subjects subjects)
          (dissoc :config))))
  
  (stop [this]
    (.close (:broker this))
    (.close (:conn this))
    (assoc this :broker nil :conn nil))

  p/AddRouter
  (add-router [{:keys [subjects]} routes opts]
    (let [router (mmc/router routes opts)
          make-handler (fn [s]
                         (log/debug "Creating new handler for subject" s "with options" opts)
                         (-> {:handler router
                              :subject s}
                             (merge (select-keys opts [:queue :stream :consumer]))))]
      ;; If we're using jetstream consumers, only register listener once and ignore
      ;; the subjects because they are configured on jetstream level.
      (if ((every-pred :stream :consumer) opts)
        [(mmc/add-listener broker (make-handler nil))]
        (->> routes
             (map first)
             (map (or (:subjects opts) subjects))
             (distinct)
             (map make-handler)
             (map (partial mmc/add-listener broker))
             (doall))))))
