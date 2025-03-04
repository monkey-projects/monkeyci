(ns monkey.ci.events.builders
  "Common event builders"
  (:require [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.containers :as co]
            [monkey.ci.events.core :as ec]))

(defn job->event
  "Converts job into something that can be converted to edn"
  [job]
  (letfn [(art->ser [a]
            (select-keys a [:id :path]))]
    (-> job
        (select-keys (concat [:status :start-time :end-time :dependencies :labels
                              :extensions :credit-multiplier :script
                              :memory :cpus :arch :work-dir
                              :save-artifacts :restore-artifacts :caches]
                             co/props))
        (mc/update-existing :save-artifacts (partial map art->ser))
        (mc/update-existing :restore-artifacts (partial map art->ser))
        (mc/update-existing :caches (partial map art->ser))
        (assoc :id (bc/job-id job)))))

(defn job-event
  "Creates a skeleton job event with basic properties"
  [type job-id build-sid]
  (ec/make-event 
   type
   :src :script
   :sid build-sid
   :job-id job-id))

(defn- job-holding-evt [type job build-sid]
  (-> (job-event type (bc/job-id job) build-sid)
      (assoc :job (job->event job))))

(defn job-pending-evt [job build-sid]
  (job-holding-evt :job/pending job build-sid))

(defn job-queued-evt [job build-sid]
  (job-holding-evt :job/queued job build-sid))

(defn job-skipped-evt [job-id build-sid]
  (job-event :job/skipped job-id build-sid))

(defn job-initializing-evt [job-id build-sid cm]
  (-> (job-event :job/initializing job-id build-sid)
      (assoc :credit-multiplier cm)))

(def job-start-evt (partial job-event :job/start))

(defn job-status-evt [type job-id build-sid {:keys [status] :as r}]
  (let [r (dissoc r :status :exception)]
    (-> (job-event type job-id build-sid)
        (assoc :status status
               :result r))))

(def job-executed-evt
  "Creates an event that indicates the job has executed, but has not been completed yet.
   Extensions may need to be applied first."
  (partial job-status-evt :job/executed))

(def job-end-evt
  "Event that indicates the job has been fully completed.  The result should not change anymore."
  (partial job-status-evt :job/end))
