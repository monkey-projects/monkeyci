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

(def get-queued ::queued)

(defn set-queued [ctx q]
  (assoc ctx ::queued q))

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

(defn execute-actions [job-ctx]
  "Interceptor that executes all action jobs in the result in a new thread.
   The job context must contain all necessary components for the job to run properly,
   such as artifacts, cache and events."
  (letfn [(execute-job [job]
            (j/execute! job (assoc job-ctx :job job)))]
    {:name ::execute-actions
     :leave (fn [ctx]
              ;; TODO Apply "before" extensions
              ;; Execute the jobs with the job context
              (->> (em/get-result ctx)
                   (map execute-job)
                   (set-running-actions ctx)))}))

(def events->result
  "Interceptor that puts events in the result"
  {:name ::events->result
   :leave (fn [ctx]
            (em/set-result ctx (get-events ctx)))})

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

;;; Handlers

(defn script-init
  "Loads all jobs in the build script, then starts the script"
  [ctx]
  (letfn [(mark-pending [job]
            (assoc job :status :pending))]
    (-> (base-event (get-build ctx) :script/start)
        (assoc :jobs (map (comp mark-pending j/job->event) (vals (get-jobs ctx)))))))

(defn- enqueue-jobs
  "Marks given jobs as enqueued in the state"
  [ctx jobs]
  (reduce (fn [r j]
            (update-job r (:id j) assoc :status :queued))
          ctx
          jobs))

(defn script-start
  "Queues all jobs that have no dependencies"
  [ctx]
  (let [jobs (get-jobs ctx)
        build-sid (b/sid (get-build ctx))]
    (if (empty? jobs)
      ;; TODO Should be warning instead of error
      (set-events ctx [(-> (script-end-evt ctx :error)
                           (bc/with-message "No jobs to run"))])
      (let [next-jobs (j/next-jobs (vals jobs))]
        (-> ctx
            (set-events (map #(j/job-queued-evt % build-sid) next-jobs))
            (enqueue-jobs next-jobs))))))

(defn script-end [ctx]
  ;; Just set the event in the result, so it can be passed to the deferred
  (em/set-result ctx (:event ctx)))

(defn job-queued
  "Executes an action job in a new thread.  For container jobs, it's up to the
   container runner implementation to handle the events."
  [ctx]
  ;; TODO Only execute it if the max number of concurrent jobs has not been reached
  (let [job-id (get-in ctx [:event :job-id])
        job (get (get-jobs ctx) job-id)]
    (cond-> ctx
      (bc/action-job? job)
      (em/set-result [job]))))

(defn job-executed
  "Runs any extensions for the job"
  [ctx]
  ;; TODO Apply "after" extensions
  (let [{:keys [job-id sid result]} (:event ctx)]
    (set-events ctx [(j/job-end-evt job-id sid result)])))

(defn- script-status
  "Determines script status according to the status of all jobs"
  [ctx]
  (if (some bc/failed? (vals (get-jobs ctx))) :error :success))

(defn job-end
  "Queues jobs that have their dependencies resolved, or ends the script
   if all jobs have been executed."
  [ctx]
  ;; Enqueue jobs that have become ready to run
  (let [{:keys [job-id sid] :as e} (:event ctx)
        upd (update-job ctx job-id merge (select-keys e [:status :result]))
        jobs (get-jobs upd)
        next-jobs (j/next-jobs (vals jobs))]
    (-> upd
        (set-events (if (empty? next-jobs)
                      [(script-end-evt ctx (script-status upd))]
                      (map #(j/job-queued-evt % sid) next-jobs)))
        (enqueue-jobs next-jobs))))

(defn- make-job-ctx
  "Constructs job context object from the route configuration"
  [ctx]
  (-> ctx
      (select-keys [:artifacts :cache :build])
      (assoc :api {:client (:api-client ctx)})))

(defn make-routes [conf]
  (let [state (emi/with-state (atom {}))]
    [[:script/initializing
      [{:handler script-init
        :interceptors [state
                       load-jobs]}]]

     [:script/start
      [{:handler script-start
        :interceptors [state
                       events->result]}]]

     [:script/end
      [{:handler script-end
        :interceptors [emi/no-result
                       (emi/realize-deferred (:result conf))]}]]

     [:job/queued
      [{:handler job-queued
        :interceptors [state
                       emi/no-result
                       (execute-actions (make-job-ctx conf))]}]]

     [:job/executed
      [{:handler job-executed
        :interceptors [events->result]}]]

     [:job/end
      [{:handler job-end
        :interceptors [state
                       events->result]}]]]))
