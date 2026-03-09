(ns monkey.ci.gui.test.scenes.build-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.build.views :as sut]))

(defscene build-status-icon
  "Large build status icon"
  [:div.d-flex.gap-2
   [sut/build-status-icon :success]
   [sut/build-status-icon :error]
   [sut/build-status-icon :running]
   [sut/build-status-icon :pending]
   [sut/build-status-icon :initializing]
   [sut/build-status-icon :unknown]])

(def build-running
  {:start-time "2024-09-24T16:52:00"
   :status :running
   :message "Test build"
   :git {:ref "refs/heads/main"}})

(def build-success
  {:start-time "2024-09-24T16:52:00"
   :end-time "2024-09-24T16:55:17"
   :status :success
   :message "Test build"
   :git {:ref "refs/heads/main"}
   :credits 17})

(defscene build-status-pending
  "Pending build status"
  [sut/build-status {:status :pending}])

(defscene build-status-initializing
  "Initializing build status"
  [sut/build-status {:status :initializing}])

(defscene build-status-running
  "Running build status"
  [sut/build-status {:status :running}])

(defscene build-status-success
  "Successful build status"
  [sut/build-status {:status :success}])

(defscene build-status-error
  "Failed build status"
  [sut/build-status {:status :error}])

(defscene build-details-running
  "Running build details"
  [sut/build-details
   {:start-time "2024-09-24T16:52:00"
    :status :running
    :message "Test build"
    :git {:ref "refs/heads/main"}}])

(defscene build-details-success
  "Successful build details"
  [sut/build-details build-success])

(defscene build-overview-running
  "Running build overview"
  [sut/overview build-running])

(defscene build-overview-success
  "Successful build overview"
  [sut/overview build-success])
