(ns monkey.ci.retry
  (:require [manifold
             [deferred :as md]
             [time :as mt]]))

(defn async-retry
  "Invokes `f`, which should return a deferred.  If it fails according to
   the configuration, it is retried after a delay depending on the `backoff`
   fn, which should return the number of msecs to wait for the next retry."
  [f {:keys [max-retries retry-if backoff]}]
  (md/loop [retries max-retries
            r nil]
    (if (zero? retries)
      r
      (md/chain
       (f)
       (fn [r]
         (if (retry-if r)
           (md/chain
            (mt/in (backoff)
                   #(md/recur (dec retries) r)))
           r))))))

(defn retry
  "Similar to `async-retry`, but for regular functions."
  [f {:keys [max-retries retry-if backoff]}]
  (loop [retries max-retries
         r nil]
    (if (zero? retries)
      r
      (let [r (f)]
        (if (retry-if r)
          (do
            (Thread/sleep (backoff))
            (recur (dec retries) r))
          r)))))

(defn constant-delay
  "Backoff fn that always returns the same interval"
  [n]
  (constantly n))

(defn- stateful-delay [init f]
  (let [v (atom nil)]
    (fn []
      (if (nil? @v)
        (reset! v init)
        (swap! v f)))))

(defn incremental-delay
  "Linearly increments delay, starting from an initial value"
  [init increment]
  (stateful-delay init (partial + increment)))

(defn exponential-delay
  "Doubles delay on each invocation"
  [init]
  (stateful-delay init (partial * 2)))

(defn with-max
  "Wraps the given delay fn but caps the delay to given limit"
  [f limit]
  (fn []
    (min (f) limit)))

(defn too-many-requests? [r]
  (= 429 (:status r)))

(defn throttle
  "Convenience function that retries the request if it returns a 429 (too many requests)
   with maximum 10 invocations and a max delay of 60 seconds."
  [f & [{:keys [retries max-delay]
         :or {retries 10 max-delay 60000}}]]
  (async-retry f {:max-retries retries
                  :retry-if too-many-requests?
                  :backoff (with-max (exponential-delay 1000) max-delay)}))
