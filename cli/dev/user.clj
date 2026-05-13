(ns user
  (:require [monkey.ci.cli
             [print :as p]
             [print-events :as pe]]))

;; Enable reflection warnings in all ns'es
(alter-var-root #'*warn-on-reflection* (constantly true))

(defn print-demo []
  (p/print-timed-msg "This is a timed message")
  (pe/script-start
   {:event
    {:jobs [{:id "first-job"
             :type :container}
            {:id "second-job"
             :type :action
             :dependencies ["first-job"]}]}})
  (p/print-job-msg "success-job" "This job has" (p/success "succeeded"))
  (p/print-job-msg "failed-job" "This job has" (p/failure "failed"))
  (p/print-cmd-start "test-job" "ls -l"))
