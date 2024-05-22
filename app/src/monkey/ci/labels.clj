(ns monkey.ci.labels
  (:require [medley.core :as mc]))

(defn matches-labels?
  "Predicate that checks if the given labels match filter `f`.  The filter
   is a list of a list of labels and values, where the first level represents a
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

(defn reconcile-labels
  "Given db labels and label objects, matches them up to return a map that
   contains db labels to insert, update and delete.  Unchanged labels are
   ignored.  Normally it's not possible to have both labels to insert and
   to delete, since one of those two will be updated.  Note that it is
   possible to have multiple labels with the same name."
  [ex new]
  (let [nv (juxt :name :value)
        ;; Drop any unchanged labels
        existing (remove (comp (set (map nv new)) nv) ex)
        new (remove (comp (set (map nv ex)) nv) new)
        ;; All remaining labels should be updated
        to-update (map merge existing new)
        ;; Any leftover labels should be inserted
        to-insert (drop (count existing) new)
        ;; Any leftover entities should be deleted
        to-delete (drop (count new) existing)]
    (->> {:insert to-insert
          :delete to-delete
          :update to-update}
         (mc/remove-vals empty?))))
