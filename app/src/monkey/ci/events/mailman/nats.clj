(ns monkey.ci.events.mailman.nats
  "Configuration for NATS subjects"
  (:require [medley.core :as mc]
            [monkey.ci.events.mailman.jms :as jms]))

(def subject-types
  ;; Use the same as jms
  jms/destination-types)

(defn make-subject-mapping [prefix]
  (->> subject-types
       (mc/map-keys #(format % prefix))
       (reduce-kv (fn [r s types]
                    (->> types
                         (map #(vector % s))
                         (into r)))
                  {})))

(defn types-to-subjects [prefix]
  (let [mapping (make-subject-mapping prefix)]
    #(get mapping %)))
