(ns monkey.ci.dispatcher.events
  (:require [clojure.tools.logging :as log]
            [monkey.ci.dispatcher.core :as dc]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci
             [storage :as st]
             [time :as t]]))

;; Context management

(defn- build->task [b]
  ;; Default requirements
  {:memory 2 :cpus 1 :build b})

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

(def get-queued ::queued)

(defn set-queued [ctx task]
  (assoc ctx ::queued task))

(defn get-queued-list [ctx]
  (::queued-list (emi/get-state ctx)))

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
                  ;; Only assign to a runner if the queue is empty
                  m (when (empty? (get-queued-list ctx))
                      (dc/assign-runner t runners))]
              (if m
                (set-assignment ctx {:runner (:id m)
                                     :resources (select-keys t [:memory :cpus])})
                ;; If it could not be assigned, it should be queued and we should terminate
                (set-queued ctx t))))})

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
            (let [a (get-assignment ctx)]
              (cond-> ctx
                a (set-state-assignment (prop (:event ctx)) a))))})

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

(def save-queued
  "Interceptor that saves a queued task.  These are tasks that could not be assigned
   immediately due to lack of resources."
  {:name ::save-queued
   :leave (fn [ctx]
            (when-let [q (get-queued ctx)]
              (log/debug "Saving queued task:" q)
              (st/save-queued-task (emi/get-db ctx) {:id (st/new-id)
                                                     :details q
                                                     :creation-time (t/now)}))
            ctx)})

(def add-to-queued-list
  "Interceptor that adds a queued task to the in-memory list in the state."
  {:name ::add-to-queued-list
   :leave (fn [ctx]
            (let [q (get-queued ctx)]
              (cond-> ctx
                q (emi/update-state update ::queued-list (fnil conj []) q))))})

#_(def unwrap-events
  "Takes the `:events` key from the result and puts that in the result instead."
  {:name ::unwrap-events
   :leave (fn [ctx]
            (update ctx :result :events))})

;; Handlers

(defn build-queued [ctx]
  (when-let [a (get-assignment ctx)]
    ;; TODO Fail build when it exceeds max capacity
    [{:type (keyword (name (:runner a)) "build-scheduled")}]))

;; Routes

(defn make-routes
  "Makes mailman routes for event handling that perform actual dispatching and resource
   management."
  [state storage]
  (let [with-state (emi/with-state state)
        use-db (emi/use-db storage)]
    [[:build/queued
      ;; Determines the runner to use and updates resources
      [{:handler build-queued
        :interceptors [with-state
                       use-db
                       add-build-task
                       add-runner-assignment
                       (save-assignment :sid)
                       save-queued
                       add-to-queued-list
                       consume-resources]}]]

     [:build/end
      ;; Frees consumed resources
      [{:handler (constantly nil)
        :interceptors [with-state
                       (load-assignment :sid)
                       (clear-assignment :sid)
                       release-resources]}]]

     ;; TODO Events received from build jobs
     [:container/job-queued []]
     [:job/end []]]))

