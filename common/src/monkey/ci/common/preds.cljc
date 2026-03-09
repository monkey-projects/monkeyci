(ns monkey.ci.common.preds
  "Common predicate functions")

(defn prop-pred
  "Returns a fn that is a predicate to match property `p` with value `v`"
  [p v]
  (comp (partial = v) p))
