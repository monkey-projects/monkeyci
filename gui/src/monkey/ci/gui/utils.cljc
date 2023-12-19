(ns monkey.ci.gui.utils)

(defn logo []
  [:img.img-fluid.rounded {:src "/img/monkeyci-large.png" :title "Placeholder Logo"}])

(defn find-by-id [id items]
  (->> items
       (filter (comp (partial = id) :id))
       (first)))
