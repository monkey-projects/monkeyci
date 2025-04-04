(ns monkey.ci.dispatcher.events
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.dispatcher
             [core :as dc]
             [state :as ds]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [storage :as st]
             [time :as t]]))

;; Context management

(def evt-sid (comp :sid :event))
(def default-resources {:memory 2 :cpus 1})

(defn build->task [b]
  {:type :build
   :details b
   :resources
   ;; Default requirements
   default-resources})

(defn job->task [job sid]
  (-> {:type :job
       :details (assoc-in job [:build :sid] sid)
       :resources
       (merge {:memory 2 :cpus 1}
              (select-keys job [:memory :cpus]))}
      (mc/assoc-some :arch (:arch job))))

(defn assignment [runner task]
  {:runner runner :task task})

(defn set-runners [ctx r]
  (emi/update-state ctx ds/set-runners r))

(defn get-runners [ctx]
  (ds/get-runners (emi/get-state ctx)))

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
  (emi/update-state ctx ds/set-assignment id a))

(defn get-state-assignment [ctx id]
  (ds/get-assignment (emi/get-state ctx) id))

(defn remove-state-assignment [ctx id]
  (emi/update-state ctx ds/remove-assignment id))

(def get-queued ::queued)

(defn set-queued [ctx task]
  (assoc ctx ::queued task))

(defn get-queued-list [ctx]
  (ds/get-queue (emi/get-state ctx)))

(defmulti get-task-sid :type)

(defmethod get-task-sid :build [r]
  (-> r :details b/sid))

(defmethod get-task-sid :job [r]
  (let [job (:details r)]
    [(-> job :build :sid) (j/job-id job)]))

;; Interceptors

(def add-build-task
  "Adds a task for the build found in the incoming event"
  {:name ::add-build-task
   :enter (fn [ctx]
            (set-task ctx (build->task (get-in ctx [:event :build]))))})

(def add-job-task
  "Adds a task for the job found in the incoming event"
  {:name ::add-job-task
   :enter (fn [ctx]
            (set-task ctx (job->task (get-in ctx [:event :job]) (evt-sid ctx))))})

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
                (set-assignment ctx (assignment (:id m) t))
                ;; If it could not be assigned, it should be queued and we should terminate
                (set-queued ctx t))))})

(defn- update-runner-resources [updater ctx]
  (let [{:keys [runner] {:keys [resources]} :task :as a} (get-assignment ctx)]
    (cond-> ctx
      a (update-runner runner updater resources))))

(def consume-resources
  "Updates state to consume resources according to scheduled task"
  {:name ::consume-resources
   :leave (partial update-runner-resources dc/use-runner-resources)})

(def release-resources
  "Same as consume, but releases the used resources instead"
  {:name ::release-resources
   :enter (partial update-runner-resources dc/release-runner-resources)})

(def with-resources
  {:name ::with-resources
   :enter (:enter release-resources)
   :leave (:leave consume-resources)})

(defn save-assignment [prop]
  "Stores assignment in state, linked to the property extracted from the event"
  {:name ::save-assignment
   :leave (fn [ctx]
            (let [a (get-assignment ctx)]
              (cond-> ctx
                a (set-state-assignment (prop (:event ctx)) a))))})

(defn load-assignment [prop]
  "Finds assignment in state, using the property extracted from the event, and
   sets it in the context."
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
                                                     :creation-time (t/now)
                                                     :task q}))
            ctx)})

(defn- find-queued-task
  "Finds a matching task in the list of queued tasks that has the same sid"
  [qt t]
  (let [sid (get-task-sid t)]
    (->> qt
         (filter (fn [q]
                   (= (get-task-sid (:task q))
                      sid)))
         first)))

(def delete-queued
  "If the result contains deleted queue items, this interceptor will remove them from
   the database."
  {:name ::delete-queued
   :leave (fn [ctx]
            (when-let [d (get-in ctx [:result :queue :removed])]
              (log/debug "Deleting these tasks from queue list:" d)
              (let [db (emi/get-db ctx)
                    l (st/list-queued-tasks db)]
                (doseq [t d]
                  (when-let [m (find-queued-task l t)]
                    (st/delete-queued-task db (:id m))))))
            ctx)})

(def add-to-queued-list
  "Interceptor that adds a queued task to the in-memory list in the state."
  {:name ::add-to-queued-list
   :leave (fn [ctx]
            (let [q (get-queued ctx)]
              (cond-> ctx
                q (emi/update-state ds/update-queue (fnil conj []) q))))})

(def result->assignment
  "Replaces the assignment in the context with the one from the result"
  {:name ::result-assignment
   :leave (fn [ctx]
            (set-assignment ctx (get-in ctx [:result :assignment])))})

(def result->queue
  "Updates the queue with values from the result"
  {:name ::result-queue
   :leave (fn [ctx]
            (let [{:keys [removed]} (get-in ctx [:result :queue])]
              (emi/update-state ctx ds/update-queue (partial remove (set removed)))))})

(def unwrap-events
  "Takes the `:events` key from the result and puts that in the result instead."
  {:name ::unwrap-events
   :leave (fn [ctx]
            (update ctx :result :events))})

;; Handlers

(defn- build-scheduled-evt [build runner]
  {:type (keyword (name runner) "build-scheduled")
   :build build
   :sid (b/sid build)})

(defn- job-scheduled-evt [job runner sid]
  {:type (keyword (name runner) "job-scheduled")
   :job job
   :job-id (j/job-id job)
   :sid sid})

(defmulti task-scheduled-evt (fn [task _] (:type task)))

(defmethod task-scheduled-evt :build [task runner]
  (build-scheduled-evt (:details task) runner))

(defmethod task-scheduled-evt :job [task runner]
  (job-scheduled-evt (:details task) runner (get-in task [:details :build :sid])))

(defn build-queued [ctx]
  (when-let [{:keys [runner] :as a} (get-assignment ctx)]
    ;; TODO Fail build when it exceeds max capacity
    {:events [(build-scheduled-evt (get-in a [:task :details]) runner)]}))

(defn job-queued [ctx]
  (when-let [{:keys [runner] :as a} (get-assignment ctx)]
    ;; TODO Fail job when it exceeds max capacity
    {:events [(job-scheduled-evt (get-in a [:task :details]) runner (evt-sid ctx))]}))

(defn task-end
  "When a build ends, resources become available again.  This means we may be able to
   schedule another task from the queue.  If so, we assign it here, and remove that task
   from the queue.  Since the released resources belong to a specific runner, we can only
   assign the task to that runner."
  [ctx]
  (let [{:keys [runner] :as a} (get-assignment ctx)
        t (dc/get-next-queued-task (get-queued-list ctx)
                                   (get-runners ctx)
                                   runner)]
    ;; What to do in case assignment is not found?  For now, we just proceed, since
    ;; actual resources will become available.  But it could be that no new queued task
    ;; can be assigned, if internal state is inaccurate.
    (when-not a
      (log/warn "Task ended, but no matching assignment was found in state"))
    (when (some? t)
      {:events [(task-scheduled-evt t runner)]
       :assignment (assignment runner t)
       :queue {:removed [t]}})))

;; Routes

(defn make-routes
  "Makes mailman routes for event handling that perform actual dispatching and resource
   management."
  [state storage]
  (let [with-state (emi/with-state state)
        use-db (emi/use-db storage)
        job-task-id (juxt :sid :job-id)]
    [[:build/queued
      ;; Determines the runner to use and updates resources
      [{:handler build-queued
        :interceptors [unwrap-events
                       with-state
                       use-db
                       add-build-task
                       add-runner-assignment
                       (save-assignment :sid)
                       save-queued
                       add-to-queued-list
                       consume-resources]}]]

     [:build/end
      ;; Frees consumed resources and schedules queued tasks
      [{:handler task-end
        :interceptors [unwrap-events
                       with-state
                       use-db
                       (load-assignment :sid)
                       (clear-assignment :sid)
                       with-resources
                       delete-queued
                       result->assignment
                       result->queue]}]]

     ;; Events received from build jobs
     [:container/job-queued
      [{:handler job-queued
        :interceptors [unwrap-events
                       with-state
                       use-db
                       add-job-task
                       add-runner-assignment
                       (save-assignment job-task-id)
                       save-queued
                       add-to-queued-list
                       consume-resources]}]]
     [:job/end
      [{:handler task-end
        :interceptors [unwrap-events
                       with-state
                       use-db
                       (load-assignment job-task-id)
                       (clear-assignment job-task-id)
                       with-resources
                       delete-queued
                       result->assignment
                       result->queue]}]]]))
