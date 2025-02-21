(ns monkey.ci.script.events
  "Mailman event routes for scripts"
  (:require [monkey.ci
             [build :as b]
             [jobs :as j]
             [script :as s]]
            [monkey.ci.events
             [core :as ec]]
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
            (set-jobs ctx (s/load-jobs (get-build ctx)
                                       (job-ctx ctx))))})

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
      (set-queued ctx (j/next-jobs jobs)))))

(defn action-job-queued
  "Executes an action job in a new thread.  For container jobs, it's up to the
   container runner implementation to handle the events."
  [ctx]
  )

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

(defn make-routes [conf]
  (let [state (emi/with-state (atom {}))]
    [[:script/initializing
      [{:handler script-init
        :initializers [load-jobs]}]]

     [:script/start
      [{:handler script-start}]]

     [:job/action-queued
      [{:handler action-job-queued}]]

     [:job/executed
      [{:handler job-executed}]]

     [:job/end
      [{:handler job-end}]]]))
