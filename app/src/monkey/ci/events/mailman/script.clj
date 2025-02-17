(ns monkey.ci.events.mailman.script
  "Mailman event routes for scripts"
  (:require [monkey.ci
             [build :as b]
             [jobs :as j]]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.build.core :as bc]))

(def get-queued ::queued)

(defn set-queued [ctx q]
  (assoc ctx ::queued q))

(def get-events ::events)

(defn set-events [ctx q]
  (assoc ctx ::events q))

(defn- base-event
  "Creates a skeleton event with basic properties"
  [build type]
  (ec/make-event
   type 
   :src :script
   :sid (b/sid build)))

(defn- script-end-evt [ctx status]
  ;; TODO Add job results
  (-> (base-event (em/get-build ctx) :script/end)
      (assoc :status status)))

(defn script-start
  "Queues all jobs that have no dependencies"
  [ctx]
  (let [jobs (-> ctx :event :script :jobs (j/next-jobs))]
    (if (empty? jobs)
      ;; TODO Should be warning instead of error
      (set-events ctx [(-> (script-end-evt ctx :error)
                           (bc/with-message "No jobs to run"))])
      (set-queued ctx jobs))))

(defn job-queued
  "It's up to the container runner to handle this.  Or if it's an action job,
   should be executed in a new thread."
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
  [[:script/start
    [{:handler script-start}]]

   [:job/queued
    [{:handler job-queued}]]

   [:job/executed
    [{:handler job-executed}]]

   [:job/end
    [{:handler job-end}]]])
