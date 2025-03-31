(ns monkey.ci.dispatcher.runtime
  "Runtime components for executing the dispatcher"
  (:require [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci.dispatcher
             [events :as de]
             [http :as dh]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.metrics.core :as metrics]
            [monkey.ci
             [oci :as oci]
             [storage :as st]]
            [monkey.ci.storage.sql]
            [monkey.ci.web.http :as http]
            [monkey.oci.container-instance.core :as ci]))

;; Should connect to the JMS message broker

(defn new-http-server [conf]
  (http/->HttpServer (:http conf) nil))

(defrecord HttpApp []
  co/Lifecycle
  (start [this]
    (assoc this :handler (dh/make-handler this)))

  (stop [this]
    this))

(defn new-http-app []
  (map->HttpApp {}))

(defn new-metrics []
  (metrics/make-registry))

(defn new-mailman [conf]
  (em/make-component (:mailman conf)))

(defn new-event-routes [_]
  (letfn [(make-routes [c]
            (de/make-routes (:state c) (:storage c)))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defn load-initial
  "Loads the initial runner resources.  Returns a list of runners with their resources."
  [conf loaders]
  (->> conf
       (map (fn [[type lc]]
              (when-let [l (get loaders type)]
                (l lc))))
       (remove nil?)
       (vec)))

(defn load-oci [{:keys [oci]}]
  (letfn [(active? [ci]
            (contains? #{"CREATING" "ACTIVE"} (:lifecycle-state ci)))]
    (let [ctx (ci/make-context oci)
          [shapes active] (-> (md/zip
                               (md/chain
                                (oci/list-instance-shapes
                                 ctx
                                 (select-keys oci [:compartment-id]))
                                (partial map :arch)
                                (partial remove (partial = :unknown)))
                               (md/chain
                                (ci/list-container-instances
                                 ctx
                                 (select-keys oci [:compartment-id]))
                                (partial filter active?)))
                              (deref))
          ;; Max number of container instances with pay-as-you-go credits
          max (get oci :max-instances 6)]
      ;; TODO Extract running build info from freeform tags and update state accordingly
      {:id :oci
       :count (- max (count active))
       :archs shapes})))

(defn load-k8s [conf]
  ;; TODO Fetch max cpu and memory and current running containers
  )

(def runner-loaders
  {:oci load-oci
   :k8s load-k8s})

(defrecord Runners [config loaders]
  co/Lifecycle
  (start [this]
    (assoc this :runners (load-initial config loaders)))

  (stop [this]
    this))

(defn new-runners [conf]
  (map->Runners {:config (:runners conf)
                 :loaders runner-loaders}))

(defn new-storage [conf]
  (st/make-storage conf))

(defn new-state [conf]
  ;; TODO Load state from db
  (atom conf))

(defn make-system [conf]
  (co/system-map
   :http-server  (co/using
                  (new-http-server conf)
                  {:app :http-app})
   :http-app     (co/using
                  (new-http-app)
                  [:metrics])
   :metrics      (new-metrics)
   :mailman      (new-mailman conf)
   :event-routes (co/using
                  (new-event-routes conf)
                  [:mailman :runners :state :storage])
   :runners      (new-runners conf)
   :state        (new-state conf)
   :storage      (new-storage conf)))
