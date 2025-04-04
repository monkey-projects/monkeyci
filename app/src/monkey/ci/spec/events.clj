(ns monkey.ci.spec.events
  "Spec definitions for events"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(s/def ::type keyword?)
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

(defmulti event-type :type)

(defmethod event-type :build/pending [_]
  (->> (s/keys :req-un [::build])
       (s/merge ::build-event)))

(defmethod event-type :build/initializing [_]
  (->> (s/keys :req-un [::build])
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
