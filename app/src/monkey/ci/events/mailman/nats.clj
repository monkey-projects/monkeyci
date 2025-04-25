(ns monkey.ci.events.mailman.nats
  "Configuration for NATS subjects"
  (:require [clj-nats-async.core :as nats]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.events.mailman.jms :as jms]
            [monkey.ci.protocols :as p]
            [monkey.mailman.core :as mmc]
            [monkey.mailman.nats.core :as mnc]))

(def subject-types
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
          conn (nats/create-nats conf)
          subjects (types-to-subjects (:prefix conf))]
      (-> this
          (assoc :conn conn
                 :broker (mnc/make-broker conn {:subject-mapper (comp subjects :type)})
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
                         (-> {:handler router
                              :subject s}
                             (merge (select-keys opts [:queue]))))]
      (->> routes
           (map first)
           (map (or (:subjects opts) subjects))
           (distinct)
           (map make-handler)
           (map (partial mmc/add-listener broker))
           (doall)))))
