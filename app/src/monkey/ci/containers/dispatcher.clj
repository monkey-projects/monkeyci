(ns monkey.ci.containers.dispatcher
  "The container dispatcher is responsible for picking pending container jobs or builds
   from a table, and assigning them to one of the available runners (either build or
   job).  Since builds and container jobs both require containers, and as such compete
   for the same resources, they are merged into one by this dispatcher.  Pending tasks
   (as we could call them) are saved in a table.  This table is checked by the dispatcher
   whenever a `job/pending`, `build/pending`, `job/end` or `build/end` event is received.
   Depending on its strategy, it picks the next task (or tasks) to start and dispaches
   them to the registered runners, according to the available resources and requirements.
   
   For example, some runners only support certain architectures, while others only have
   limited resources available.  Some runners can have priority over others, probably to
   reduce costs (i.e. we would use the k8s runner before an oci runner).

   The table is required to be able to pick from multiple waiting tasks, as opposed to
   an event queue, which will only allow to process them in sequence.  Storing this
   in memory is not an option, since multiple replicas also mean multiple dispatchers.
   So this table is a way for them to sync up.  It also keeps data over restarts.

   Both builds and container jobs require infra resources to run, so it's logical that
   there is some sort of coordination.  Various approaches are possible, but for now we
   have chosen the simplest approach: a single process is responsible for allocating the
   resources.  It polls from the job and build topics as long as resources are available.
   If no more resources are available, it stops polling.  Normally resources should become
   available again, and polling can resume.  Any containers that are unassignable because
   they make requirements that can never be met (e.g. too many cpu's), are immediately 
   marked as failed.
  
   The available resources can be requested by the dispatcher, but for efficiency's sake
   it would be better that this information is kept locally, and updated by received
   events (`build/start`, `job/start`, `build/end` and `job/end`)."
  (:require [monkey.ci.events.mailman.interceptors :as emi]))

(defn dispatch
  "Performs a dispatching round using the given configuration.  The configuration
   consists of functions that provide the current tasks to execute, the executors
   to which tasks can be assigned, a strategy that determines the executor for a
   task and a function that actually executes the task.

   Returns a list of execution results for each assigned task."
  [{:keys [get-tasks get-executors execute-task strategy]}]
  (letfn [(assign-task [task]
            (when-let [e (strategy task (get-executors))]
              (execute-task task e)))]
    (->> (get-tasks)
         (map assign-task)
         (remove nil?)
         (doall))))

(defn matches-arch? [[{:keys [arch]} {:keys [archs]}]]
  (or (nil? arch)
      (contains? (set archs) arch)))

(defn matches-cpus? [[{:keys [cpus]} {avail :cpus}]]
  (<= cpus avail))

(defn matches-mem? [[{:keys [memory]} {avail :memory}]]
  (<= memory avail))

(def matches-k8s?
  "Checks if the given k8s runner can run the given task"
  (every-pred matches-arch? matches-cpus? matches-mem?))

(defn has-capacity? [[_ {:keys [count]}]]
  (pos? count))

(def matches-oci?
  "Checks if the given oci runner can run the task"
  (every-pred matches-arch? has-capacity?))

(def matchers {:k8s matches-k8s?
               :oci matches-oci?})

(defn assign-runner
  "Given a task (either build or container job), determines the runner to use.  The task
   contains cpu and memory requirements, and optional architecture (amd or arm).  The
   runners provide available resources and supported architectures."
  [task runners]
  (letfn [(get-matcher [{:keys [id]}]
            (get matchers id (constantly false)))
          (matches? [r]
            ((get-matcher r) [task r]))]
    (->> runners
         (filter matches?)
         (first))))

(defn consume-k8s [r {:keys [cpus memory]}]
  (-> r
      (update :cpus - cpus)
      (update :memory - memory)))

(defn consume-oci [r _]
  (update r :count dec))

(def consumers {:k8s consume-k8s
                :oci consume-oci})

(defn use-runner-resources
  "Updates runner by decreasing available resources"
  [r task]
  (let [upd (get consumers (:id r) identity)]
    (upd r task)))

;;; Mailman event routing

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
                  m (assign-runner t runners)]
              (when m
                (set-assignment ctx {:runner (:id m)
                                     :resources (select-keys t [:memory :cpus])}))))})

(def consume-resources
  "Updates state to consume resources according to scheduled task"
  {:name ::consume-resources
   :leave (fn [ctx]
            (let [{:keys [runner resources]} (get-assignment ctx)]
              (update-runner ctx runner use-runner-resources resources)))})

;; Handlers

(defn build-queued [ctx]
  (let [a (get-assignment ctx)]
    [{:type (keyword (name (:runner a)) "build-scheduled")}]))

;; Routes

(defn make-routes
  "Makes mailman routes for event handling that perform actual dispatching and resource
   management."
  [conf]
  (let [with-state (emi/with-state (atom conf))]
    [[:build/queued [{:handler build-queued
                      :interceptors [with-state
                                     add-build-task
                                     add-runner-assignment
                                     consume-resources]}]]
     [:build/end []]
     [:container/job-queued []]
     [:job/end []]]))
