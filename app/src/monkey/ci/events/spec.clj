(ns monkey.ci.events.spec
  "Spec definitions for events.  All events sent (and received) should match `::event`."
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build :as build]
             [common :as c]
             [script :as ss]]
            [monkey.ci.spec.job.common :as jc]))

(def build-event-types
  #{:build/triggered :build/queued :build/pending :build/initializing
    :build/start :build/end :build/canceled :build/updated})

(def script-event-types
  #{:script/initializing :script/start :script/end})

(def job-event-types
  #{:job/pending :job/queued :job/initializing :job/start :job/end :job/skipped
    :job/executed :job/blocked :job/unblocked})

(def container-event-types
  #{:container/pending :container/initializing :container/start :container/end
    :container/job-queued})

(def sidecar-event-types
  #{:sidecar/start :sidecar/end})

(def command-event-types
  #{:command/start :command/end})

(def oci-event-types
  #{:oci/build-scheduled :oci/job-scheduled})

(def k8s-event-types
  #{:k8s/build-scheduled :k8s/job-scheduled})

(def event-types
  (set/union build-event-types
             script-event-types
             job-event-types
             container-event-types
             sidecar-event-types
             command-event-types
             oci-event-types
             k8s-event-types))

(s/def ::type event-types)
(s/def ::message string?)
(s/def ::time c/ts?)
;; Event source (e.g. api, script, build-agent,...)
(s/def ::src keyword?)
(s/def ::done? boolean?)

(s/def ::event-base (s/keys :req-un [::type ::time]
                            :opt-un [::message ::src]))

(s/def ::job-id ::jc/id)

;; Job as passed in events
(s/def ::job
  (s/keys :req-un [::jc/id ::jc/type]
          :opt-un [::jc/caches ::jc/save-artifacts ::jc/restore-artifacts ::jc/dependencies
                   ::jc/blocked]))

(s/def ::jobs (s/coll-of ::job))

(s/def ::build-ref-event
  (->> (s/keys :req-un [::build/sid])
       (s/merge ::event-base)))

(s/def ::build-holding-event
  (->> (s/keys :req-un [::build/build])
       (s/merge ::build-ref-event)))

(s/def ::job-event
  (->> (s/keys :req-un [::job-id])
       (s/merge ::build-ref-event)))

(s/def ::runner-details map?)

(defmulti event-type :type)

(defmethod event-type :build/triggered [_]
  ::build-holding-event)

(defmethod event-type :build/pending [_]
  ::build-holding-event)

(defmethod event-type :build/queued [_]
  ::build-holding-event)

(defmethod event-type :build/initializing [_]
  (->> (s/keys :opt-un [::runner-details])
       (s/merge ::build-holding-event)))

(defmethod event-type :build/start [_]
  (->> (s/keys :req-un [::jc/credit-multiplier])
       (s/merge ::build-ref-event)))

(defmethod event-type :build/end [_]
  (->> (s/keys :req-un [::build/status])
       (s/merge ::build-ref-event)))

(defmethod event-type :build/canceled [_]
  ::build-ref-event)

(defmethod event-type :build/updated [_]
  ::build-holding-event)

(defmethod event-type :script/initializing [_]
  (->> (s/keys :req-un [::ss/script-dir])
       (s/merge ::build-ref-event)))

(defmethod event-type :script/start [_]
  (->> (s/keys :req-un [::jobs])
       (s/merge ::build-ref-event)))

(defmethod event-type :script/end [_]
  (->> (s/keys :req-un [::ss/status])
       (s/merge ::build-ref-event)))

(defmethod event-type :job/initializing [_]
  (->> (s/keys :req-un [::jc/credit-multiplier])
       (s/merge ::job-event)))

(defmethod event-type :job/pending [_]
  (-> (s/keys :req-un [:script/job])
      (s/merge ::job-event)))

(defmethod event-type :job/queued [_]
  (-> (s/keys :req-un [:script/job])
      (s/merge ::job-event)))

(defmethod event-type :job/start [_]
  ::job-event)

(defmethod event-type :job/skipped [_]
  ::job-event)

(defmethod event-type :job/blocked [_]
  ::job-event)

(s/def ::job-status-event
  (->> (s/keys :req-un [::jc/status]
               :opt-un [::jc/result])
       (s/merge ::job-event)))

(defmethod event-type :job/executed [_]
  ;; Indicates the job has been executed, but not yet ended.  Extensions may
  ;; have to be applied yet.
  ::job-status-event)

(defmethod event-type :job/end [_]
  ;; Indicates the job has been fully completed.
  ::job-status-event)

(defmethod event-type :sidecar/start [_]
  ::job-event)

(defmethod event-type :sidecar/end [_]
  ::job-status-event)

(defmethod event-type :container/pending [_]
  ::job-event)

(defmethod event-type :container/start [_]
  ::job-event)

(defmethod event-type :container/end [_]
  (s/merge ::job-event
           (s/keys :req-un [::done? ::jc/result])))

(s/def ::event (s/multi-spec event-type :type))
