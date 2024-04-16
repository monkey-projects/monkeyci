(ns monkey.ci.labels)

(defn matches-labels?
  "Predicate that checks if the given labels match filter `f`.  The filter
   is a list of a list labels and values, where the first level represents a
   disjunction (logical 'or') and the second a conjunction (logical 'and')."
  [f labels]
  (letfn [(filter-applies? [{:keys [label value]}]
            ;; TODO Add support for regexes
            (= value (get labels label)))
          (conjunction-applies? [parts]
            (every? filter-applies? parts))
          (disjunction-applies? [parts]
            (or (empty? parts)
                (some conjunction-applies? parts)))]
    (disjunction-applies? f)))

(defn apply-label-filters
  "Given a single set of parameters with label filters, checks if the given
   labels match.  If there is at least one filter in the params' `:label-filters`
   for which all labels in the conjunction match, this returns `true`.  If
   the params don't have any labels, this assumes all labels match."
  [labels params]
  (matches-labels? (:label-filters params) labels))

(defn labels->map [l]
  (->> (map (juxt :name :value) l)
       (into {})))

(defn filter-by-label [repo entities]
  (filter (partial apply-label-filters (labels->map (:labels repo))) entities))
