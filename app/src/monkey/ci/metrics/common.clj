(ns monkey.ci.metrics.common
  (:require [clojure.string :as cs]))

(defn counter-id [parts]
  (->> parts
       (map name)
       (cs/join "_")
       (str "monkeyci_")))

(def metric-id counter-id)
