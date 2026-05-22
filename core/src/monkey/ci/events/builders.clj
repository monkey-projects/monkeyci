(ns monkey.ci.events.builders
  "Common event builders"
  (:require [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.core :as ec]
            [monkey.ci.time :as t]))

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
        ;; Keep everything except the non-serializable properties
        (dissoc :action :init)
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

(defn job-unblocked-evt [job-id build-sid]
  (job-event :job/unblocked job-id build-sid))

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

(defn exit-code->status [exit]
  (if (and (number? exit) (zero? exit))
    :success
    :error))


(def build-sid-props [:org-id :repo-id :build-id])

(def build-props->sid
  "Constructs sid from build properties"
  (apply juxt build-sid-props))

(def build-sid-length 3)

(def build-sid
  "Gets the sid from the build"
  (some-fn :sid build-props->sid))

(def credit-multiplier :credit-multiplier)

(defn build-evt [type build & keyvals]
  (apply ec/make-event type :sid (build-sid build) keyvals))

(defn- with-build-evt [type build]
  (build-evt type
             build
             :build build))

(defn build-triggered-evt [build]
  (-> (with-build-evt :build/triggered build)
      ;; sid at this point is only repo sid, since the build id still needs to be assigned
      (assoc :sid [(:org-id build) (:repo-id build)])))

(defn build-pending-evt [build]
  (with-build-evt :build/pending build))

(defn build-init-evt [build]
  (with-build-evt :build/initializing build))

(defn build-start-evt [build]
  (build-evt :build/start
             build
             :credit-multiplier (credit-multiplier build)))

(defn build-end-evt
  "Creates a `build/end` event"
  [build & [exit-code]]
  (-> (build-evt :build/end build)
      (assoc :status (exit-code->status exit-code)
             ;; TODO Remove this
             :build (-> build
                        (assoc :end-time (t/now))
                        (mc/update-existing :git dissoc :ssh-keys)
                        (mc/assoc-some :status (exit-code->status exit-code))))
      (mc/assoc-some :message (:message build))))
