(ns user
  (:require [monkey.ci.cli.print :as p]))

;; Enable reflection warnings in all ns'es
(alter-var-root #'*warn-on-reflection* (constantly true))

(defn print-demo []
  (p/print-timed-msg "This is a timed message")
  (p/print-job-msg "success-job" "This job has" (p/success "succeeded"))
  (p/print-job-msg "failed-job" "This job has" (p/failure "failed")))
