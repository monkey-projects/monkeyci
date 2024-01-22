(ns monkey.ci.labels)

(defn apply-label-filters
  "Given a single set of parameters with label filters, checks if the given
   labels match.  If there is at least one filter in the params' `:label-filters`
   for which all labels in the conjunction match, this returns `true`.  If
   the params don't have any labels, this assumes all labels match."
  [labels params]
  (letfn [(filter-applies? [{:keys [label value]}]
            ;; TODO Add support for regexes
            (= value (get labels label)))
          (conjunction-applies? [parts]
            (every? filter-applies? parts))
          (disjunction-applies? [parts]
            (or (empty? parts)
                (some conjunction-applies? parts)))]
    (disjunction-applies? (:label-filters params))))

(defn labels->map [l]
  (->> (map (juxt :name :value) l)
       (into {})))

(defn filter-by-label [repo entities]
  (filter (partial apply-label-filters (labels->map (:labels repo))) entities))
