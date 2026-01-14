(ns monkey.ci.script.events
  "Mailman event routes for scripts"
  (:require [buddy.core.codecs :as bcc]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [executor :as me]]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [extensions :as ext]
             [jobs :as j]
             [vault :as v]]
            [monkey.ci.build
             [api :as ba]
             [core :as bc]]
            [monkey.ci.events
             [builders :as eb]
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.core :as s]
            [monkey.ci.vault.common :as vc]))

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
  [ctx]
  (get (get-jobs ctx) (get-in ctx [:event :job-id])))

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
  (letfn [(encrypt-env [encrypter job]
            (letfn [(encrypt-val [v]
                      (@encrypter v))]
              (mc/update-existing job :container/env (partial mc/map-vals encrypt-val))))
          (filter-jobs [f jobs]
            (cond->> jobs
              f (j/filter-jobs (if (fn? f) f (comp (set f) j/job-id)))))]
    {:name ::load-jobs
     :enter (fn [ctx]
              (let [build (get-build ctx)
                    job-ctx (select-keys (get-initial-job-ctx ctx) [:build :api :archs])
                    encrypter (delay ; Lazy so we don't unnecessarily decrypt the dek
                                (let [dek (-> (ba/decrypt-key (get-api-client ctx) (:dek build))
                                              (bcc/b64->bytes))
                                      iv (v/cuid->iv (b/org-id build))]
                                  (fn [v]
                                    (vc/encrypt dek iv v))))]
                (log/debug "Loading script jobs using context" job-ctx
                           "and filter" (get-job-filter ctx))
                (->> (s/load-jobs (get-build ctx) job-ctx)
                     (filter-jobs (get-job-filter ctx))
                     (group-by j/job-id)
                     ;; Encrypt container env vars (possibly sensitive information)
                     ;; FIXME Scripts may want to read back the env vars passed to
                     ;; container jobs, which will be encrypted at that point.  So it
                     ;; may be better to only encrypt them when they are sent out in
                     ;; an event.
                     (mc/map-vals (comp (partial encrypt-env encrypter) first))
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

(def execute-action
  "Interceptor that executes the job in the input event in a new thread, provided
   it's an action job.  The job context must contain all necessary components for 
   the job to run properly, such as artifacts, cache and events."
  ;; TODO Make pool size configurable
  (let [executor (me/fixed-thread-executor 5)]
    (letfn [(post-job-error [job mailman {:keys [job-id sid]} ex]
              (em/post-events mailman
                              [(j/job-end-evt job-id sid (-> bc/failure
                                                             (bc/with-message (ex-message ex))))]))
            (execute-job [job ctx]
              (log/debug "Scheduling action job for execution:" (j/job-id job))
              (let [job-ctx (emi/get-job-ctx ctx)]
                ;; Execute the job onto a fixed thread executor, to limit number of
                ;; concurrent actions.
                (me/with-executor executor
                  (-> (j/execute! job job-ctx)
                      ;; Catch exceptions and mark job failed in that case
                      (md/catch (partial post-job-error job (:mailman job-ctx) (:event ctx)))))))]
      {:name ::execute-action
       :enter (fn [ctx]
                (let [job (get-job-from-state ctx)]
                  (cond-> ctx
                    (bc/action-job? job)
                    ;; Execute the jobs with the job context
                    (set-running-actions [(execute-job job ctx)]))))})))

(def enqueue-jobs
  "Interceptor that enqueues all jobs indicated in the `job/queued` events in the result"
  {:name ::enqueue-jobs
   :leave (fn [ctx]
            (let [job-ids (->> (em/get-result ctx)
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

;;; Handlers

(defn script-init
  "Loads all jobs in the build script, then starts the script"
  [ctx]
  (letfn [(mark-pending [job]
            (assoc job :status :pending))]
    (-> (base-event (get-build ctx) :script/start)
        (assoc :jobs (map (comp mark-pending eb/job->event) (vals (get-jobs ctx)))))))

(defn make-job-queued-evt [job build-sid]
  (if (j/should-block? job)
    (j/job-blocked-evt (j/job-id job) build-sid)
    (j/job-queued-evt job build-sid)))

(defn script-start
  "Queues all jobs that have no dependencies"
  [ctx]
  (let [jobs (get-jobs ctx)
        build-sid (b/sid (get-build ctx))
        next-jobs (j/next-jobs (vals jobs))]
    (log/debug "Starting script with" (count jobs) "job(s):" (keys jobs))
    (if (empty? next-jobs)
      ;; TODO Should be warning instead of error      
      [(-> (script-end-evt ctx :error)
           (bc/with-message "No jobs to run"))]
      (let [next-jobs (j/next-jobs (vals jobs))]
        (map #(make-job-queued-evt % build-sid) next-jobs)))))

(defn script-end [ctx]
  (log/debug "Script ended, realizing deferred")
  ;; Just set the event in the result, so it can be passed to the deferred
  (:event ctx))

(defn job-queued
  "Dispatches queued event for action or container job, depending on the type."
  [ctx]
  (letfn [(job-queued-evt [t job dek]
            (-> (j/job-queued-evt job (build-sid ctx))
                (assoc :type t
                       :dek dek)))]
    (let [job (get-job-from-state ctx)
          dek (:dek (get-build ctx))]
      ;; Action jobs do not result in an event, instead they are executed immediately.
      (when (bc/container-job? job)
        [(job-queued-evt :container/job-queued job dek)]))))

(defn job-unblocked
  "Received when a blocked job becomes unblocked: schedule it immediately."
  [ctx]
  [(j/job-queued-evt (get-job-from-state ctx) (b/sid (get-build ctx)))])

(defn job-executed
  "Runs any extensions for the job in interceptors, then ends the job."
  [ctx]
  (let [{:keys [job-id sid status result]} (:event ctx)
        job-ctx (emi/get-job-ctx ctx)]
    ;; Safeguard: treat `nil` states as success, otherwise the job is re-queued
    (when (nil? status)
      (log/warn "Got job/executed event without status, treating it as a success"))
    [(j/job-end-evt job-id sid (assoc (-> job-ctx :job :result) :status (or status :success)))]))

(defn- script-status
  "Determines script status according to the status of all jobs"
  [ctx]
  (cond
    (build-canceled? ctx)
    :canceled
    (some bc/failed? (vals (get-jobs ctx)))
    :error
    :else
    :success))

(defn- filter-jobs [ctx pred]
  (->> (get-jobs ctx)
       vals
       (filter pred)))

(defn- pending-jobs [ctx]
  (filter-jobs ctx j/pending?))

(defn- pending-or-blocked-jobs [ctx]
  (filter-jobs ctx (some-fn j/pending? j/blocked?)))

(defn- active-jobs [ctx]
  (filter-jobs ctx j/active?))

(defn- make-jobs-skipped-evts [ctx jobs]
  (map #(j/job-skipped-evt (j/job-id %) (build-sid ctx)) jobs))

(defn job-end
  "Queues jobs that have their dependencies resolved, or ends the script
   if all jobs have been executed, or the build has been canceled.  Blocked
   jobs are not queued, but marked as blocked."
  [ctx]
  ;; Enqueue jobs that have become ready to run
  (let [all-jobs (vals (get-jobs ctx))
        next-jobs (j/next-jobs all-jobs)
        active-jobs (j/filter-jobs j/active? all-jobs)]
    (log/debug "Active jobs:" (map j/job-id active-jobs))
    (if (or (build-canceled? ctx)
            (and (empty? next-jobs) (empty? active-jobs)))
      ;; No more jobs eligible for execution, end the script
      (->> (pending-jobs ctx)
           (make-jobs-skipped-evts ctx)
           (into [(script-end-evt ctx (script-status ctx))]))
      ;; Otherwise, enqueue next jobs
      (map #(make-job-queued-evt % (build-sid ctx)) next-jobs))))

(defn build-canceled
  "Marks all pending or blocked jobs in the script as skipped.  If no other jobs remain,
   the script is ended."
  [ctx]
  (cond-> (->> (pending-or-blocked-jobs ctx)
               (make-jobs-skipped-evts ctx))
    (empty? (active-jobs ctx)) (into [(script-end-evt ctx :canceled)])))

(defn make-job-ctx
  "Constructs job context object from the route configuration"
  [conf]
  (-> conf
      (select-keys [:artifacts :cache :mailman :build :archs])
      (assoc :api {:client (:api-client conf)})))

(defn make-routes [{:keys [result] :as conf}]
  (let [state (emi/with-state (atom (-> conf
                                        (select-keys [:build :filter])
                                        (assoc ::initial-job-ctx (make-job-ctx conf)))))]
    [[:script/initializing
      [{:handler script-init
        :interceptors [handle-script-error
                       state
                       load-jobs]}]]

     [:script/start
      [{:handler script-start
        :interceptors [handle-script-error
                       state
                       enqueue-jobs]}]]

     [:script/end
      [{:handler script-end
        :interceptors [emi/no-result
                       (emi/realize-deferred result)]}]]

     [:job/queued
      ;; Raised when a new job is queued.  This handler splits it up according to
      ;; type and executes before-extensions.  Action jobs are executed immediately.
      [{:handler job-queued
        :interceptors [emi/handle-job-error
                       state
                       with-job-ctx
                       ext/before-interceptor
                       execute-action]}]]

     [:job/unblocked
      [{:handler job-unblocked
        :interceptors [emi/handle-job-error
                       state
                       add-job-ctx]}]]

     [:job/executed
      ;; Handle this for both container and action jobs
      [{:handler job-executed
        :interceptors [emi/handle-job-error
                       state
                       add-job-ctx
                       add-result-to-ctx
                       ext/after-interceptor]}]]

     [:job/end
      [{:handler job-end
        :interceptors [state
                       enqueue-jobs
                       set-job-result]}]]

     [:build/canceled
      [{:handler build-canceled
        :interceptors [state
                       mark-canceled]}]]]))
