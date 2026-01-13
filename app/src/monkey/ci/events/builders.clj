(ns monkey.ci.events.builders
  "Common event builders"
  (:require [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.containers :as co]
            [monkey.ci.events.core :as ec]))

(defn script-event [type build-sid]
  (ec/make-event 
   type
   :src :script
   :sid build-sid))

(defn script-init-evt [build-sid script-dir]
  (-> (script-event :script/initializing build-sid)
      (assoc :script-dir script-dir)))

(defn job->event
  "Converts job into something that can be converted to edn"
  [job]
  (letfn [(art->ser [a]
            (select-keys a [:id :path]))
          (serialize-props [j]
            (reduce (fn [r k]
                      (mc/update-existing r k (partial map art->ser)))
                    j
                    [:save-artifacts :restore-artifacts :caches]))]
    (-> job
        ;; Keep everything except the action, which is a function
        (dissoc :action)
        ;; Force into map because records are not serializable
        (as-> m (into {} m))
        (serialize-props)
        (assoc :id (bc/job-id job)))))

(defn job-event
  "Creates a skeleton job event with basic properties"
  [type job-id build-sid]
  (-> (script-event type build-sid)
      (assoc :job-id job-id)))

(defn- job-holding-evt [type job build-sid]
  (-> (job-event type (bc/job-id job) build-sid)
      (assoc :job (job->event job))))

(defn job-pending-evt [job build-sid]
  (job-holding-evt :job/pending job build-sid))

(defn job-queued-evt [job build-sid]
  (job-holding-evt :job/queued job build-sid))

(defn job-skipped-evt [job-id build-sid]
  (job-event :job/skipped job-id build-sid))

(defn job-blocked-evt [job-id build-sid]
  (job-event :job/blocked job-id build-sid))

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
