(ns monkey.ci.gui.utils)

(defn find-by-id [id items]
  (->> items
       (filter (comp (partial = id) :id))
       (first)))
