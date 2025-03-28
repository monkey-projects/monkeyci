(ns monkey.ci.dispatcher.core
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
  (:require [clojure.tools.logging :as log]))

(defn matches-arch? [[{:keys [arch]} {:keys [archs] :as r}]]
  (or (nil? arch)
      (contains? (set archs) arch)))

(defn matches-cpus? [[{{:keys [cpus]} :resources} {avail :cpus}]]
  (<= cpus avail))

(defn matches-mem? [[{{:keys [memory]} :resources} {avail :memory}]]
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

(defn- get-matcher [runner-id]
  (get matchers runner-id (constantly false)))

(defn assign-runner
  "Given a task (either build or container job), determines the runner to use.  The task
   contains cpu and memory requirements, and optional architecture (amd or arm).  The
   runners provide available resources and supported architectures."
  [task runners]
  (letfn [(matches? [r]
            ((get-matcher (:id r)) [task r]))]
    (->> runners
         (filter matches?)
         (first))))

(defn consume-k8s [r {:keys [cpus memory] :as res}]
  (log/debug "Consuming k8s resources:" res)
  (-> r
      (update :cpus - cpus)
      (update :memory - memory)))

(defn consume-oci [r _]
  (log/debug "Consuming oci resources")
  (update r :count dec))

(def consumers {:k8s consume-k8s
                :oci consume-oci})

(defn use-runner-resources
  "Updates runner by decreasing available resources"
  [r task]
  (let [upd (get consumers (:id r) (fn [r _] r))]
    (upd r task)))

(defn release-k8s [r {:keys [cpus memory] :as res}]
  (log/debug "Releasing k8s resources:" r)
  (-> r
      (update :cpus + cpus)
      (update :memory + memory)))

(defn release-oci [r _]
  (log/debug "Releasing oci resources")
  (update r :count inc))

(def releasers {:k8s release-k8s
                :oci release-oci})

(defn release-runner-resources
  "Updates runner by increasing available resources"
  [r task]
  (let [upd (get releasers (:id r) identity)]
    (upd r task)))

(def oldest-first
  (partial sort-by :creation-time))

(defn- get-runner [runners id]
  (->> runners
       (filter (comp (partial = id) :id))
       first))

(defn arch-filter [runner]
  (partial filter (comp matches-arch?
                        #(vector (:task %) runner))))

(defn resource-filter [runner]
  (partial filter (comp (get-matcher (:id runner))
                        #(vector (:task %) runner))))

(defn exclusivity-filter
  "Creates a filter fn that removes all tasks that can be run by other runners, if
   at least one task is available that can only be run by this runner."
  [runners runner]
  (fn [tasks]
    (letfn [(task-runners [t]
              (->> runners
                   (filter (fn [r]
                             (log/debug "Arch filter for" r)
                             (matches-arch? [(:task t) r])))
                   (mapv :id)))]
      (let [singles (->> tasks
                         (map (fn [t]
                                [t (task-runners t)]))
                         (filter (comp (partial = [(:id runner)]) second))
                         (map first))]
        (if (not-empty singles)
          singles
          tasks)))))

(defn get-next-queued-task
  "Finds the next queued task to schedule for the runner.  Tasks are filtered like so:
    1. Drop all tasks that have mismatching architectures.
    2. If there are tasks that can only be run on this runner, keep only those.
    3. Drop tasks that require too many resources.
    4. Sort oldest first.
    5. Take the first in the list.
  
   Rule (2) is required to avoid large tasks constantly getting pushed back because smaller
   ones keep getting selected.  If a task that required many resources can only be run on 
   this specific runner, we should wait until it has sufficient resources available."
  [qt runners runner-id]
  (let [runner (get-runner runners runner-id)
        ;; Rules are applied last to first
        rules (comp oldest-first
                    (resource-filter runner)
                    (exclusivity-filter runners runner)
                    (arch-filter runner))]
    ;; Sort the list of tasks according to priority rules, then take the first
    (->> qt
         (rules)
         (first))))
