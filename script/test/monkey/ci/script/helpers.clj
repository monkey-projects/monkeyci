(ns monkey.ci.script.helpers
  "Test helper functions"
  (:require [clojure.core.async :as ca]
            [monkey.mailman.core-async :as mmca]))

(defn store-events
  "Given a core-async broker, reads and stores all sent events.  Returns
   an atom that holds the events.  Make sure to close the broker channel
   eventually, otherwise the internal go-loop will go on forever."
  [broker]
  (let [recv (atom [])
        ch (mmca/get-channel broker)]
    (ca/go-loop [v (ca/<! ch)]
      (when v
        (swap! recv conj v)
        (recur (ca/<! ch))))
    recv))

(defn wait-for-evt [broker pred]
  (let [ch (mmca/get-channel broker)]
    (first (ca/alts!! [(ca/go-loop [v (ca/<! ch)]
                         (when v
                           (if (pred v)
                             v
                             (recur (ca/<! ch)))))
                       (ca/timeout 1000)]))))

(defn wait-until [pred timeout]
  (let [delay 100]
    (loop [e 0]
      (if-let [v (pred)]
        v
        (if (> e timeout)
          :timeout
          (do
            (Thread/sleep delay)
            (recur (+ e delay))))))))
