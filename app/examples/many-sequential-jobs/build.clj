(ns build
  (:require [monkey.ci.api :as m]))

(def n-jobs 20)

(defn make-job [idx]
  (cond-> (m/action-job (str "job-" (inc idx))
                        (-> m/success
                            (m/with-message (str "Job executed: " (inc idx)))))
    ;; Make each job dependent on the previous one
    (pos? idx) (m/depends-on [(str "job-" idx)])))

(->> (range n-jobs)
     (mapv make-job))
