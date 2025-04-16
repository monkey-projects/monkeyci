(ns monkey.ci.events.mailman
  "Mailman-style event handling"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman
             [interceptors :as emi]
             [jms :as emj]]
            [monkey.mailman
             [core :as mmc]
             [interceptors :as mi]
             [jms :as mj]
             [manifold :as mm]]))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def global-interceptors [emi/trace-evt
                          emi/add-time
                          (mi/sanitize-result)])

(defn make-router [routes]
  (mmc/router routes
              {:interceptors global-interceptors}))

(defn merge-routes
  "Merges multiple routes together into one routing config"
  [routes & others]
  (letfn [(merge-one [a b]
            (merge-with concat (into {} a) (into {} b)))]
    (loop [res []
           todo (cons routes others)]
      (if (empty? todo)
        (seq res)
        (recur (merge-one res (first todo)) (rest todo))))))

;;; Components

(defprotocol AddRouter
  (add-router [broker routes opts] "Registers a listener for given routes in the broker"))

(defmulti make-component :type)

(defrecord GenericComponent [broker routes]
  co/Lifecycle
  (start [this]
    (assoc this
           :listener (mmc/add-listener broker (make-router (:routes routes)))))

  (stop [{:keys [listener] :as this}]
    (when listener
      (mmc/unregister-listener listener))
    (dissoc this :listener))

  AddRouter
  (add-router [this routes opts]
    [(mmc/add-listener broker (mmc/router routes opts))]))

(defn make-generic-component [broker]
  (->GenericComponent broker nil))

(defmethod make-component :manifold [_]
  (make-generic-component (mm/manifold-broker {})))

(defrecord JmsComponent [broker]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (let [dests (emj/topic-destinations config)
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
    (-> this
        (assoc :broker nil)
        (dissoc :bridge)))

  AddRouter
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

(defmethod make-component :jms [config]
  (map->JmsComponent {:config config}))

;; Generic component that can be used to add a new route listener to mailman
(defrecord RouteComponent [routes make-routes mailman]
  co/Lifecycle
  (start [this]
    (let [routes (make-routes this)]
      (log/debug "Registering" (count routes) "routes in broker:" (map first routes))
      (assoc this
             :routes routes
             :listeners (add-router mailman routes (-> {:interceptors global-interceptors}
                                                       (merge (select-keys this [:destinations])))))))

  (stop [{:keys [listeners] :as this}]
    (when listeners
      (log/debug "Unregistering" (count listeners) "listeners")
      (doseq [l listeners]
        (mmc/unregister-listener l)))
    (dissoc this :listeners)))

(defn post-events
  "Posts events using the broker in the mailman component"
  [mm events]
  (log/trace "Posting events:" events)
  (some-> mm
          :broker
          (mmc/post-events events)))
