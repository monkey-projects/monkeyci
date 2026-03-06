(ns monkey.ci.gui.test.scenes.job-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.job.views :as sut]))

(defscene job-summary-basic
  "Display basic job details"
  [sut/job-summary
   {:start-time 1726210910713
    :end-time   1726210930713
    :message "Test message"
    :status :running}])

(defscene job-summary-long-msg
  "Job details with long message"
  [sut/job-summary
   {:status :success
    :start-time 1726210910713
    :end-time   1726210930713
    :message "Lorem ipsum odor amet, consectetuer adipiscing elit. Praesent ipsum quis praesent; mauris nam mattis egestas egestas donec. Ultricies molestie vitae mus neque lacinia mauris tristique fusce. Atortor et praesent molestie molestie vulputate eleifend sit. Dignissim faucibus ut et consectetur lectus feugiat libero integer. Auctor senectus semper primis semper amet justo magna natoque enim."}])

(defscene job-summary-blocked
  [sut/job-summary
   {:status :blocked}])

(defscene job-summary-queued
  [sut/job-summary
   {:status :queued}])

(defscene job-details-multi-port
  [sut/job-details
   {:type :container
    :container/image "docker.io/test-img:test-tag"
    :script ["test script"]
    :expose [8080 8081]
    :agent {:address "1.2.3.5"
            :ports {20001 8080
                    20002 8081}}}])

(defscene job-details-single-port
  [sut/job-details
   {:type :container
    :container/image "docker.io/clojure:tools-deps-trixie"
    :script ["clojure -M -m nrepl.cmdline -p 7888 -b 0.0.0.0"]
    :expose [7888]
    :agent {:address "2a01:4f8:c013:5630::1"
            :ports {20342 7888}}}])

(defscene job-tabs-blocked
  [sut/overview-tabs
   {:status :blocked}])

(defscene unblocking-btn
  [sut/unblock-btn
   {:status :blocked}
   true])
