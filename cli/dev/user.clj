(ns user
  (:require [babashka.fs :as fs]
            [monkey.ci.cli
             [print :as p]
             [run :as run]]
            [monkey.ci.cli.print.scrolling :as ps]))

;; Enable reflection warnings in all ns'es
(alter-var-root #'*warn-on-reflection* (constantly true))

(defn print-demo []
  (p/print-timed-msg "This is a timed message")
  (ps/script-start
   {:event
    {:jobs [{:id "first-job"
             :type :container}
            {:id "second-job"
             :type :action
             :dependencies ["first-job"]}]}})
  (p/print-job-msg "success-job" "This job has" (p/success "succeeded"))
  (p/print-job-msg "failed-job" "This job has" (p/failure "failed"))
  (p/print-cmd-start "test-job" "ls -l"))

(defn run-script [dir]
  (run/build {:dir dir
              :lib-coords {:local/root (str (fs/canonicalize "../script"))}}))
