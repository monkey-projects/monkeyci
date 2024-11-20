(require '[monkey.ci.build.core :as bc])

(def n-jobs 20)

(defn make-job [idx]
  (bc/action-job (str "job-" (inc idx))
                 (-> bc/success
                     (bc/with-message (str "Job executed: " (inc idx))))))

(->> (range n-jobs)
     (mapv make-job))
