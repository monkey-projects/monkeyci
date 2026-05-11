(ns monkey.ci.jobs
  "GraalVM-compatible subset of job-related functions needed by container runners.
   Contains only pure accessors, predicates, constants and event-builder aliases.
   Does not include manifold, action job execution or artifact/cache handling —
   those remain in the app/ subproject."
  (:require [monkey.ci.build.core :as bc]
            [monkey.ci.events.builders :as eb]))

;;;; Accessors

(def job-id
  "Gets the id of a job."
  bc/job-id)

(def deps
  "Gets job dependencies."
  :dependencies)

(def status
  "Gets job execution status."
  :status)

(def work-dir
  "Gets the job working directory."
  :work-dir)

(def labels
  "Gets job labels."
  :labels)

(def save-artifacts
  "Gets artifact save configurations for the job."
  :save-artifacts)

;;;; Status predicates

(def pending?  (comp (some-fn nil? (partial = :pending)) status))
(def queued?   (comp (partial = :queued) status))
(def running?  (comp (partial = :running) status))
(def failed?   (comp #{:error :failure} status))
(def success?  (comp (partial = :success) status))
(def active?   (comp #{:queued :initializing :running} status))
(def blocked?  (comp (partial = :blocked) status))

;;;; Timeout constants

(def default-job-timeout
  "Default container job timeout in milliseconds (20 minutes)."
  (* 20 60 1000))

(def max-job-timeout
  "Maximum allowed container job timeout in milliseconds (6 hours)."
  (* 6 60 60 1000))

;;;; Sizing helpers

(defn size->cpus
  "Returns the number of CPUs to allocate for the given job."
  [job]
  (or (:size job) (:cpus job) 1))

(defn size->mem
  "Returns the amount of memory in GB to allocate for the given job."
  [job]
  (or (some-> (:size job) (* 2))
      (:memory job)
      2))

;;;; Event builder aliases

(def job-status-evt     eb/job-status-evt)
(def job-pending-evt    eb/job-pending-evt)
(def job-queued-evt     eb/job-queued-evt)
(def job-skipped-evt    eb/job-skipped-evt)
(def job-blocked-evt    eb/job-blocked-evt)
(def job-initializing-evt eb/job-initializing-evt)
(def job-start-evt      eb/job-start-evt)
(def job-executed-evt   eb/job-executed-evt)
(def job-end-evt        eb/job-end-evt)
