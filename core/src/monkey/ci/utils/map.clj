(ns monkey.ci.utils.map
  "Map utility functions, GraalVM-native-image compatible."
  (:require [clojure.walk :as cw]))

(defn- prune-map
  "Removes nil values and empty collections / strings from a map."
  [m]
  (reduce-kv (fn [acc k v]
               (if (or (nil? v)
                       (and (seqable? v) (empty? v)))
                 acc
                 (assoc acc k v)))
             {}
             m))

(defn prune-tree
  "Recursively removes nil values and empty collections from all nested maps.
   Equivalent to monkey.ci.utils/prune-tree in app/ but without the medley/manifold
   dependency chain."
  [t]
  (cw/prewalk (fn [x]
                (cond-> x
                  (map? x) prune-map))
              t))
