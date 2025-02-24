(ns monkey.ci.events.mailman
  "Mailman-style event handling"
  (:require [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman
             [bridge :as emb]
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

(defrecord ManifoldComponent [broker routes]
  co/Lifecycle
  (start [this]
    (let [broker (mm/manifold-broker {})]
      (assoc this
             :broker broker
             :listener (mmc/add-listener broker (make-router (:routes routes))))))

  (stop [{:keys [listener] :as this}]
    (when listener
      (mmc/unregister-listener listener))
    (dissoc this :listener))

  AddRouter
  (add-router [this routes opts]
    (mmc/add-listener broker (mmc/router routes opts))))

(defmethod make-component :manifold [_]
  (map->ManifoldComponent {}))

(defrecord JmsComponent [broker routes]
  co/Lifecycle
  (start [{:keys [config] :as this}]
    (let [broker (mj/jms-broker (assoc config
                                       :destination-mapper (comp (emj/event-destinations config) :type)))
          router (make-router (:routes routes))
          bridge-dest (get-in config [:bridge :dest])
          dests (emj/event-destinations config)
          add-listeners (fn [{:keys [destinations] :as c}]
                          (assoc c :listeners (add-router c
                                                          (:routes routes)
                                                          {:interceptors global-interceptors})))]
      ;; TODO Add listeners for each destination referred to by route event types
      ;; but split up the routes so only those for the destination are added
      (cond-> this
        true (dissoc :config) ; no longer needed
        true (assoc :broker broker
                    :destinations dests)
        true (add-listeners)
        ;; Listen to legacy events, if configured
        bridge-dest (assoc :bridge (mmc/add-listener broker {:destination bridge-dest
                                                             :handler (mmc/router emb/bridge-routes)})))))

  (stop [this]
    (when broker
      (mj/disconnect broker))
    (-> this
        (assoc :broker nil)
        (dissoc :bridge :listeners)))

  AddRouter
  (add-router [{:keys [destinations]} routes opts]
    (let [router (mmc/router routes opts)]
      (->> routes
           (map first)
           (map destinations)
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
      (assoc this
             :routes routes
             :listener (add-router mailman routes {:interceptors global-interceptors}))))

  (stop [{:keys [listener] :as this}]
    (when listener
      (mmc/unregister-listener listener))
    (dissoc this :listener)))

(defn post-events
  "Posts events using the broker in the mailman component"
  [mm events]
  (some-> mm
          :broker
          (mmc/post-events events)))
