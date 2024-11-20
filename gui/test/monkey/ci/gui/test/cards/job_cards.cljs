(ns monkey.ci.gui.test.cards.job-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.job.views :as sut]
            [reagent.core]
            [re-frame.db :as rdb]))

(defcard-rg job-details-basic
  "Display basic job details"
  [sut/job-details
   {:start-time 1726210910713
    :end-time   1726210930713
    :message "Test message"
    :status :running}])

(defcard-rg job-details-long-msg
  "Job details with long message"
  [sut/job-details
   {:status :success
    :start-time 1726210910713
    :end-time   1726210930713
    :message "Lorem ipsum odor amet, consectetuer adipiscing elit. Praesent ipsum quis praesent; mauris nam mattis egestas egestas donec. Ultricies molestie vitae mus neque lacinia mauris tristique fusce. Atortor et praesent molestie molestie vulputate eleifend sit. Dignissim faucibus ut et consectetur lectus feugiat libero integer. Auctor senectus semper primis semper amet justo magna natoque enim."}])
