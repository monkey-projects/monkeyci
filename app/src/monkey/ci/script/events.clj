(ns monkey.ci.script.events
  "Mailman event routes for scripts"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [script :as s]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.build.core :as bc]))

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

(defn job-ctx
  "Creates a job execution context from the event context"
  [ctx]
  {:build (get-build ctx)
   :api (:api ctx)})

;;; Event builders

(defn- base-event
  "Creates a skeleton event with basic properties"
  [build type]
  (ec/make-event
   type 
   :src :script
   :sid (b/sid build)))

(defn- script-end-evt [ctx status]
  ;; TODO Add job results
  (-> (base-event (get-build ctx) :script/end)
      (assoc :status status)))

;;; Interceptors

(def load-jobs
  "Interceptor that loads jobs from the location pointed to by the script-dir 
   and adds them to the state."
  {:name ::load-jobs
   :enter (fn [ctx]
            (->> (s/load-jobs (get-build ctx)
                              (job-ctx ctx))
                 (group-by j/job-id)
                 (mc/map-vals first)
                 (set-jobs ctx)))})

(defn execute-action [job-ctx]
  "Interceptor that executes the job in the input event in a new thread, provided
   it's an action job.  The job context must contain all necessary components for 
   the job to run properly, such as artifacts, cache and events."
  (letfn [(execute-job [job]
            (j/execute! job (assoc job-ctx :job job)))]
    {:name ::execute-action
     :enter (fn [ctx]
              (let [job (get (get-jobs ctx) (get-in ctx [:event :job-id]))]
                ;; TODO Apply "before" extensions
                ;; TODO Only execute it if the max number of concurrent jobs has not been reached
                ;; Execute the jobs with the job context
                (set-running-actions ctx (when (bc/action-job? job) [(execute-job job)]))))}))

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

(def handle-script-error
  "Marks script as failed"
  {:name ::script-error-handler
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking script as failed" ex)
            (assoc ctx :result [(-> (script-end-evt ctx :error)
                                    (assoc :message (ex-message ex)))]))})

;;; Handlers

(defn script-init
  "Loads all jobs in the build script, then starts the script"
  [ctx]
  (letfn [(mark-pending [job]
            (assoc job :status :pending))]
    (-> (base-event (get-build ctx) :script/start)
        (assoc :jobs (map (comp mark-pending j/job->event) (vals (get-jobs ctx)))))))

(defn script-start
  "Queues all jobs that have no dependencies"
  [ctx]
  (let [jobs (get-jobs ctx)
        build-sid (b/sid (get-build ctx))]
    (log/debug "Starting script with" (count jobs) "job(s):" (keys jobs))
    (if (empty? jobs)
      ;; TODO Should be warning instead of error      
      [(-> (script-end-evt ctx :error)
           (bc/with-message "No jobs to run"))]
      (let [next-jobs (j/next-jobs (vals jobs))]
        (map #(j/job-queued-evt % build-sid) next-jobs)))))

(defn script-end [ctx]
  ;; Just set the event in the result, so it can be passed to the deferred
  (:event ctx))

(defn job-executed
  "Runs any extensions for the job"
  [ctx]
  ;; TODO Apply "after" extensions
  (let [{:keys [job-id sid status result]} (:event ctx)]
    [(j/job-end-evt job-id sid (assoc result :status status))]))

(defn- script-status
  "Determines script status according to the status of all jobs"
  [ctx]
  (if (some bc/failed? (vals (get-jobs ctx))) :error :success))

(defn job-end
  "Queues jobs that have their dependencies resolved, or ends the script
   if all jobs have been executed."
  [ctx]
  ;; Enqueue jobs that have become ready to run
  (let [next-jobs (j/next-jobs (vals (get-jobs ctx)))]
    (if (empty? next-jobs)
      ;; TODO Also add job/skipped for any leftover jobs
      [(script-end-evt ctx (script-status ctx))]
      (map #(j/job-queued-evt % (get-in ctx [:event :sid])) next-jobs))))

(defn- make-job-ctx
  "Constructs job context object from the route configuration"
  [ctx]
  (-> ctx
      (select-keys [:artifacts :cache :events :build])
      (assoc :api {:client (:api-client ctx)})))

(defn make-routes [{:keys [build] :as conf}]
  (let [state (emi/with-state (atom {:build build}))]
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
                       (emi/realize-deferred (:result conf))]}]]

     [:job/queued
      [{:handler (constantly nil)
        :interceptors [state
                       (execute-action (make-job-ctx conf))]}]]

     [:job/executed
      [{:handler job-executed}]]

     [:job/end
      [{:handler job-end
        :interceptors [state
                       enqueue-jobs
                       set-job-result]}]]]))
