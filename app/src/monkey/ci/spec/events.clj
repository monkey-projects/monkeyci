(ns monkey.ci.spec.events
  "Spec definitions for events"
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

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
(s/def ::time int?)
(s/def ::src keyword?)

(s/def ::event-base (s/keys :req-un [::type ::time]
                            :opt-un [::message ::src]))

(s/def ::build map?) ; TODO Specify
(s/def ::credit-multiplier int?)
(s/def ::job-id c/id?)
(s/def ::result map?)

(s/def ::build-event
  (->> (s/keys :req-un [:build/sid])
       (s/merge ::event-base)))

(s/def ::job-event
  (->> (s/keys :req-un [::job-id])
       (s/merge ::build-event)))

(s/def ::runner-details map?)

(defmulti event-type :type)

(defmethod event-type :build/pending [_]
  (->> (s/keys :req-un [::build])
       (s/merge ::build-event)))

(defmethod event-type :build/initializing [_]
  (->> (s/keys :req-un [::build]
               :opt-un [::runner-details])
       (s/merge ::build-event)))

(defmethod event-type :build/start [_]
  (->> (s/keys :req-un [::credit-multiplier])
       (s/merge ::build-event)))

(defmethod event-type :build/end [_]
  (->> (s/keys :req-un [:build/status])
       (s/merge ::build-event)))

(defmethod event-type :build/canceled [_]
  ::build-event)

(defmethod event-type :build/updated [_]
  (->> (s/keys :req-un [::build])
       (s/merge ::build-event)))

(defmethod event-type :script/initializing [_]
  (->> (s/keys :req-un [:script/script-dir])
       (s/merge ::build-event)))

(defmethod event-type :script/start [_]
  (->> (s/keys :req-un [:script/jobs])
       (s/merge ::build-event)))

(defmethod event-type :script/end [_]
  (->> (s/keys :req-un [:script/status])
       (s/merge ::build-event)))

(defmethod event-type :job/initializing [_]
  (->> (s/keys :req-un [::credit-multiplier])
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
  (->> (s/keys :req-un [:job/status]
               :opt-un [::result])
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

(s/def ::event (s/multi-spec event-type :type))
