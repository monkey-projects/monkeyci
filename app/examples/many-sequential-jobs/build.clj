(require '[monkey.ci.build.core :as bc])

(def n-jobs 20)

(defn make-job [idx]
  (cond-> (bc/action-job (str "job-" (inc idx))
                         (-> bc/success
                             (bc/with-message (str "Job executed: " (inc idx)))))
    ;; Make each job dependent on the previous one
    (pos? idx) (bc/depends-on [(str "job-" idx)])))

(->> (range n-jobs)
     (mapv make-job))
