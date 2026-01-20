(ns monkey.ci.gui.test.scenes.job-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.job.views :as sut]))

(defscene job-details-basic
  "Display basic job details"
  [sut/job-details
   {:start-time 1726210910713
    :end-time   1726210930713
    :message "Test message"
    :status :running}])

(defscene job-details-long-msg
  "Job details with long message"
  [sut/job-details
   {:status :success
    :start-time 1726210910713
    :end-time   1726210930713
    :message "Lorem ipsum odor amet, consectetuer adipiscing elit. Praesent ipsum quis praesent; mauris nam mattis egestas egestas donec. Ultricies molestie vitae mus neque lacinia mauris tristique fusce. Atortor et praesent molestie molestie vulputate eleifend sit. Dignissim faucibus ut et consectetur lectus feugiat libero integer. Auctor senectus semper primis semper amet justo magna natoque enim."}])

(defscene job-details-blocked
  [sut/job-details
   {:status :blocked}])

(defscene job-details-queued
  [sut/job-details
   {:status :queued}])

(defscene job-tabs-blocked
  [sut/details-tabs
   {:status :blocked}])

(defscene unblocking-btn
  [sut/unblock-btn
   {:status :blocked}
   true])
