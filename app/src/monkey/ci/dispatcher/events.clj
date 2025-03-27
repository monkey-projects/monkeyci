(ns monkey.ci.dispatcher.events
  (:require [monkey.ci.dispatcher.core :as dc]
            [monkey.ci.events.mailman.interceptors :as emi]))

;; Context management

(defn- build->task [_]
  ;; Default requirements
  {:memory 2 :cpus 1})

(defn set-runners [ctx r]
  (emi/update-state ctx assoc :runners r))

(defn get-runners [ctx]
  (:runners (emi/get-state ctx)))

(defn update-runner [ctx id f & args]
  (letfn [(replace-runner [runners]
            (let [match (->> runners
                             (filter (comp (partial = id) :id))
                             (first))]
              (vec (replace {match (apply f match args)} runners))))]
    (emi/update-state ctx update :runners replace-runner)))

(def get-assignment ::assignment)

(defn set-assignment [ctx a]
  (assoc ctx ::assignment a))

(def get-task ::task)

(defn set-task [ctx a]
  (assoc ctx ::task a))

(defn set-state-assignment [ctx id a]
  (emi/update-state ctx assoc-in [:assignments id] a))

(defn get-state-assignment [ctx id]
  (get-in (emi/get-state ctx) [:assignments id]))

(defn remove-state-assignment [ctx id]
  (emi/update-state ctx update :assignments dissoc id))

;; Interceptors

(def add-build-task
  "Adds a task for the build found in the incoming event"
  {:name ::add-build-task
   :enter (fn [ctx]
            (set-task ctx (build->task (get-in ctx [:event :build]))))})

(def add-runner-assignment
  "Interceptor that assigns a runner to the task stored in context"
  {:name ::add-runner-assignment
   :enter (fn [ctx]
            (let [t (get-task ctx)
                  runners (get-runners ctx)
                  m (dc/assign-runner t runners)]
              (when m
                (set-assignment ctx {:runner (:id m)
                                     :resources (select-keys t [:memory :cpus])}))))})

(defn- update-runner-resources [updater ctx]
  (let [{:keys [runner resources]} (get-assignment ctx)]
    (update-runner ctx runner updater resources)))

(def consume-resources
  "Updates state to consume resources according to scheduled task"
  {:name ::consume-resources
   :leave (partial update-runner-resources dc/use-runner-resources)})

(def release-resources
  "Same as consume, but releases the used resources instead"
  {:name ::release-resources
   :enter (partial update-runner-resources dc/release-runner-resources)})

(defn save-assignment [prop]
  "Stores assignment in state, linked to the property extracted from the event"
  {:name ::save-assignment
   :leave (fn [ctx]
            (set-state-assignment ctx (prop (:event ctx)) (get-assignment ctx)))})

(defn load-assignment [prop]
  "Finds assignment in state, using the property extracted from the event"
  {:name ::load-assignment
   :enter (fn [ctx]
            (->> ctx
                 :event
                 prop
                 (get-state-assignment ctx)
                 (set-assignment ctx)))})

(defn clear-assignment [prop]
  "Removes assignment from state, linked to the property extracted from the event"
  {:name ::clear-assignment
   :leave (fn [ctx]
            (remove-state-assignment ctx (prop (:event ctx))))})

;; Handlers

(defn build-queued [ctx]
  (let [a (get-assignment ctx)]
    ;; TODO Fail build when no assignment (means no available capacity)
    [{:type (keyword (name (:runner a)) "build-scheduled")}]))

;; Routes

(defn listener-routes
  "Makes mailman routes for event handling that perform actual dispatching and resource
   management.  These routes are responsible for releasing assigned resources and as
   such should share state with the poll routes."
  [state]
  (let [with-state (emi/with-state state)]
    [[:build/end
      ;; Frees consumed resources
      [{:handler (constantly nil)
        :interceptors [with-state
                       (load-assignment :sid)
                       (clear-assignment :sid)
                       release-resources]}]]

     ;; TODO Events received from build jobs
     [:job/end []]]))

(defn poll-routes
  "Creates routes to be used when polling.  We use polling because we don't want to pick
   container tasks from the queues if we know there is no capacity available."
  [state]
  (let [with-state (emi/with-state state)]
    [[:build/queued
      ;; Determines the runner to use and updates resources
      [{:handler build-queued
        :interceptors [with-state
                       add-build-task
                       add-runner-assignment
                       (save-assignment :sid)
                       consume-resources]}]]

     ;; TODO Events received from build jobs
     [:container/job-queued []]]))
