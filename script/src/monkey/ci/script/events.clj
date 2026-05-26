(ns monkey.ci.script.events
  "Mailman event handlers for scripts"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events
             [builders :as eb]
             [core :as ec]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script
             [build :as b]
             [jobs :as j]
             [load :as l]]
            [monkey.mailman.core :as mmc]))

;;; Context management

(def get-events ::events)

(defn set-events [ctx q]
  (assoc ctx ::events q))

(def get-jobs (comp :jobs emi/get-state))

(defn set-jobs [ctx jobs]
  (emi/update-state ctx assoc :jobs jobs))

(defn update-job
  "Applies `f` to the job with given id in the state"
  [ctx job-id f & args]
  (apply emi/update-state ctx update :jobs mc/update-existing job-id f args))

(def get-build (comp :build emi/get-state))

(defn set-build [ctx build]
  (emi/update-state ctx assoc :build build))

(def get-running-actions ::running-actions)

(defn set-running-actions [ctx a]
  (assoc ctx ::running-actions a))

(def job-id (comp :job-id :event))
(def build-sid (comp :sid :event))

(defn- get-job-from-state
  "Gets current job from the jobs stored in the state"
  ([ctx id]
   (get (get-jobs ctx) id))
  ([ctx]
   (get-job-from-state ctx (get-in ctx [:event :job-id]))))

(defn- get-job-from-ctx
  "Retrieves job from job ctx"
  [ctx]
  (-> ctx emi/get-job-ctx :job))

(defn set-initial-job-ctx [ctx job-ctx]
  (emi/update-state ctx assoc ::initial-job-ctx job-ctx))

(def get-initial-job-ctx (comp ::initial-job-ctx emi/get-state))
(def get-job-filter (comp :filter emi/get-state))

(defn get-job-ctx
  "Gets or creates a job context from the state for the current job."
  [ctx]
  (or (some-> (emi/get-state ctx) (get-in [::job-ctx (job-id ctx)]))
      (get-initial-job-ctx ctx)))

(defn set-job-ctx [ctx job-ctx]
  (emi/update-state ctx assoc-in [::job-ctx (job-id ctx)] job-ctx))

(defn set-build-canceled [ctx]
  (log/debug "Canceling build" (build-sid ctx))
  (emi/update-state ctx assoc ::build-canceled true))

(defn build-canceled? [ctx]
  (true? (::build-canceled (emi/get-state ctx))))

(def get-api-client (comp :client :api get-initial-job-ctx))

;;; Event builders

(defn- base-event
  "Creates a skeleton event with basic properties"
  [build type]
  (ec/make-event
   type 
   :src :script
   :sid (b/sid build)))

(defn- script-end-evt [ctx status]
  (-> (base-event (get-build ctx) :script/end)
      (assoc :status status)))
;;; Interceptors

(def load-jobs
  "Interceptor that loads jobs from the location pointed to by the script-dir 
   and adds them to the state."
  (letfn [(filter-jobs [f jobs]
            (cond->> jobs
              f (j/filter-jobs (if (fn? f) f (comp (set f) j/job-id)))))]
    {:name ::load-jobs
     :enter (fn [ctx]
              (let [job-ctx (select-keys (get-initial-job-ctx ctx) [:build :api :archs])]
                (log/debug "Loading script jobs using context" job-ctx
                           "and filter" (get-job-filter ctx))
                (->> (l/load-jobs (get-build ctx) job-ctx)
                     (filter-jobs (get-job-filter ctx))
                     (group-by j/job-id)
                     (set-jobs ctx))))}))

(def add-job-ctx
  "Interceptor that adds the job context, taken from state.  An initial context should
   be already added to the state.  Extensions may modify this context.  It is passed
   to the jobs on execution."
  {:name ::add-job-ctx
   :enter (fn [ctx]
            (let [jc (-> (get-job-ctx ctx)
                         (assoc :job (get-job-from-state ctx)))]
              (emi/set-job-ctx ctx jc)))})

(def with-job-ctx
  "Adds job context from state in `enter`, and saves any updated context back on `leave`"
  {:name ::with-job-ctx
   :enter (:enter add-job-ctx)
   :leave (fn [ctx]
            (set-job-ctx ctx (emi/get-job-ctx ctx)))})

(defn add-job-retriever
  "Interceptor that augments the job context with an api function that can be used to
   retrieve other job details."
  [state]
  {:name ::add-job-retriever
   :enter (fn [ctx]
            (emi/set-job-ctx ctx (-> (emi/get-job-ctx ctx)
                                     (assoc-in [:api :jobs] (fn [id]
                                                              (get-in @state [:jobs id]))))))})

(def execute-action
  "Interceptor that executes the job in the input event in a new thread, provided
   it's an action job.  The job context must contain all necessary components for 
   the job to run properly, such as artifacts, cache and events."
  (letfn [(post-job-error [job mailman {:keys [job-id sid]} ex]
            (mmc/post-events mailman
                             [(eb/job-end-evt job-id sid (-> bc/failure
                                                             (bc/with-message (ex-message ex))))]))
          (execute-job [job ctx]
            (log/debug "Scheduling action job for execution:" (j/job-id job))
            (let [job-ctx (emi/get-job-ctx ctx)]
              ;; Execute the job onto a fixed thread executor, to limit number of
              ;; concurrent actions.
              #_(-> (j/execute! job job-ctx)
                  ;; Catch exceptions and mark job failed in that case
                  (md/catch (partial post-job-error job (:mailman job-ctx) (:event ctx))))))]
    {:name ::execute-action
     :enter (fn [ctx]
              ;; Use the job from the context, so extensions can modify it
              (let [job (get-job-from-ctx ctx)]
                (cond-> ctx
                  (bc/action-job? job)
                  ;; Execute the jobs with the job context
                  (set-running-actions [(execute-job job ctx)]))))}))

(def enqueue-jobs
  "Interceptor that enqueues all jobs indicated in the `job/queued` events in the result"
  {:name ::enqueue-jobs
   :leave (fn [ctx]
            (let [job-ids (->> (emi/get-result ctx)
                               (filter (comp (partial = :job/queued) :type))
                               (map :job-id))]
              (log/debug "Enqueueing these jobs:" job-ids)
              (reduce (fn [r id]
                        (update-job r id assoc :status :queued))
                      ctx
                      job-ids)))})

(def set-job-result
  "Sets job result according to the event"
  {:name ::set-job-result
   :enter (fn [ctx]
            (let [{:keys [job-id] :as e} (:event ctx)]
              (update-job ctx job-id merge (select-keys e [:status :result]))))})

(def add-result-to-ctx
  "Adds the result from the event to the job context.  Used by extensions."
  {:name ::add-result-to-ctx
   :enter (fn [{:keys [event] :as ctx}]
            (emi/update-job-ctx ctx assoc-in [:job :result] (merge (:result event)
                                                                   (select-keys event [:status]))))})

(def handle-script-error
  "Marks script as failed"
  {:name ::script-error-handler
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking script as failed" ex)
            (assoc ctx :result [(-> (script-end-evt ctx :error)
                                    (assoc :message (ex-message ex)))]))})

(def mark-canceled
  "Marks build as canceled, so no other jobs will be enqueued."
  {:name ::mark-canceled
   :enter (fn [ctx]
            (cond-> ctx
              (= (build-sid ctx) (b/sid (get-build ctx)))
              (set-build-canceled)))})

(def update-job-init
  "Updates the job info with details from the job/initializing event"
  {:name ::update-job-init
   :leave (fn [ctx]
            (update-job ctx (job-id ctx) assoc :agent (get-in ctx [:event :agent])))})
