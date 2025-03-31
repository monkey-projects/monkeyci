(ns monkey.ci.dispatcher.runtime
  "Runtime components for executing the dispatcher"
  (:require [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [meta-merge.core :as mm]
            [monkey.ci.dispatcher
             [events :as de]
             [http :as dh]
             [state :as ds]]
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

(defn ci->task
  "Extracts task info from a container instance"
  [ci]
  (let [sc (:shape-config ci)]
    {:cpus (:ocpus sc)
     :memory (:memory-in-g-bs sc)
     :arch (oci/shape->arch (:shape ci))}))

(defn ci->task-id
  "Determines task id from container instance details"
  [ci]
  (let [{job-id "job-id" :as tags} (:freeform-tags ci)]
    (cond-> (-> tags
                (select-keys ["customer-id" "repo-id" "build-id"])
                (vals))
      job-id (-> (vector)
                 (conj job-id)))))

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
      (-> (ds/set-runners {} [{:id :oci
                                :count (- max (count active))
                                :archs shapes}])
          (as-> state
              (reduce (fn [s ci]
                        (->> (de/assignment :oci (ci->task ci))
                             (ds/set-assignment s (ci->task-id ci))))
                      state
                      active))))))

(defn load-k8s [conf]
  ;; TODO Fetch max cpu and memory and current running containers
  )

(defn new-storage [conf]
  (st/make-storage conf))

(defn load-initial
  "Loads the initial state.  Returns a map of runners with their resources and 
   state."
  [conf loaders]
  (->> conf
       (reduce (fn [r [type lc]]
                 (let [l (get loaders type)]
                   (cond-> r
                     l (mm/meta-merge r (l lc)))))
               {})))

(defrecord InitialState [config loaders]
  co/Lifecycle
  (start [this]
    (merge this (load-initial config loaders)))

  (stop [this]
    this))

(def runner-loaders
  {:oci load-oci
   :k8s load-k8s})

(defn new-state [conf]
  (map->InitialState {:config (:runners conf)
                      :loaders runner-loaders}))

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
                  [:mailman :state :storage])
   :state        (new-state conf)
   :storage      (new-storage conf)))
