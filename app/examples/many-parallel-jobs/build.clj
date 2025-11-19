(ns build
  (:require [monkey.ci.api :as m]))

(def n-jobs 20)

(defn make-job [idx]
  (m/action-job (str "job-" (inc idx))
                (-> m/success
                    (m/with-message (str "Job executed: " (inc idx))))))

(->> (range n-jobs)
     (mapv make-job))
