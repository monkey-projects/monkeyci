(ns monkey.ci.local.common
  (:require [monkey.ci.utils :as u]))

(defn set-interceptors
  "Sets the interceptors of all route handlers"
  [routes i]
  (mapv (fn [r]
          (u/update-nth r 1 #(u/update-nth % 0 assoc :interceptors i)))
        routes))
