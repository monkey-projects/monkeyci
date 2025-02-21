(ns monkey.ci.script.events
  "Mailman event routes for scripts"
  (:require [medley.core :as mc]
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
              ;; Execute the jobs with the job context
              (->> (em/get-result ctx)
                   (map execute-job)
                   (set-running-actions ctx)))}))

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
        (assoc :jobs (map (comp mark-pending j/job->event) (get-jobs ctx))))))

(defn script-start
  "Queues all jobs that have no dependencies"
  [ctx]
  (let [jobs (get-jobs ctx)]
    (if (empty? jobs)
      ;; TODO Should be warning instead of error
      (set-events ctx [(-> (script-end-evt ctx :error)
                           (bc/with-message "No jobs to run"))])
      ;; TODO Change this into job/queued events instead
      (set-queued ctx (j/next-jobs jobs)))))

(defn action-job-queued
  "Executes an action job in a new thread.  For container jobs, it's up to the
   container runner implementation to handle the events."
  [ctx]
  (when-let [job (get (get-jobs ctx) (get-in ctx [:event :job-id]))]
    (when (bc/action-job? job)
      [job])))

(defn job-executed
  "Runs any extensions for the job"
  [ctx]
  ;; TODO
  nil)

(defn job-end
  "Queues jobs that have their dependencies resolved, or ends the script
   if all jobs have been executed."
  [ctx]
  ;; TODO
  (set-events ctx [(script-end-evt ctx :success)]))

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
        :interceptors [state]}]]

     [:job/queued
      [{:handler action-job-queued
        :interceptors [state
                       emi/no-result
                       (execute-actions (make-job-ctx conf))]}]]

     [:job/executed
      [{:handler job-executed}]]

     [:job/end
      [{:handler job-end}]]]))
