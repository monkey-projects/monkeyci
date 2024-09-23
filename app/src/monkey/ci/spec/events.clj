(ns monkey.ci.spec.events
  "Spec definitions for events"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.build]))

(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::time int?)
(s/def ::src keyword?)

(s/def ::event-base (s/keys :req-un [::type ::time]
                            :opt-un [::message ::src]))

(defmulti event-type :type)

(s/def ::build map?) ; TODO Specify
(s/def ::credit-multiplier int?)

(defmethod event-type :build/initializing [_]
  (->> (s/keys :req-un [:build/sid ::build])
       (s/merge ::event-base)))

(defmethod event-type :build/start [_]
  (->> (s/keys :req-un [:build/sid ::credit-multiplier])
       (s/merge ::event-base)))

(defmethod event-type :build/end [_]
  (->> (s/keys :req-un [:build/sid :build/status])
       (s/merge ::event-base)))

(s/def ::event (s/multi-spec event-type :type))
