(ns monkey.ci.common.jobs)

(defn sort-by-deps
  "Sorts the given list of jobs so those with least dependencies come first,
   and jobs that are dependent on them come later."
  [jobs]
  (loop [rem (->> jobs (sort-by :id) vec)
         proc? #{}
         res []]
    (if (empty? rem)
      res
      (let [next-jobs (->> rem
                           (filter (comp (partial every? proc?) :dependencies)))]
        (if (empty? next-jobs)
          ;; Safety, should not happen
          (concat res rem)
          (recur (remove (set next-jobs) rem)
                 (clojure.set/union proc? (set (map :id next-jobs)))
                 (concat res next-jobs))))))  )
