(ns print
  (:require [monkey.ci.local.print :as p]))

(defn build-start []
  (p/build-start
   p/console-printer
   {:event
    {:sid ["test-org" "test-repo" "test-build"]}}))

(defn build-init []
  (p/build-init
   p/console-printer
   {:event
    {:build
     {:build-id "test-build"
      :checkout-dir "/tmp/test-checkout"
      :script
      {:script-dir ".monkeyci/"}}}}))

(defn build-end-success []
  (p/build-end
   p/console-printer
   {:event
    {:status :success}}))

(defn build-end-failure []
  (p/build-end
   p/console-printer
   {:event
    {:status :failure
     :message "Build error"}}))

(defn script-start []
  (p/script-start
   p/console-printer
   {:event
    {:jobs
     [{:id "job-1"}
      {:id "job-2"}]}}))

(defn script-end-with-msg []
  (p/script-end
   p/console-printer
   {:event
    {:message "Test msg"}}))

(defn script-end-without-msg []
  (p/script-end
   p/console-printer
   {}))

(defn job-start []
  (p/job-start
   p/console-printer
   {:event
    {:job-id "test-job"}}))

(defn job-end-success []
  (p/job-end
   p/console-printer
   {:event
    {:job-id "test-job"
     :status :success}}))

(defn job-end-failure []
  (p/job-end
   p/console-printer
   {:event
    {:job-id "test-job"
     :status :failure
     :result
     {:output "Some error occurred"}}}))
