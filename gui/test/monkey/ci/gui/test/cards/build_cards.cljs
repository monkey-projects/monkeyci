(ns monkey.ci.gui.test.cards.build-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.build.views :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg build-status-icon
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

(defcard-rg build-status-pending
  "Pending build status"
  [sut/build-status {:status :pending}])

(defcard-rg build-status-initializing
  "Initializing build status"
  [sut/build-status {:status :initializing}])

(defcard-rg build-status-running
  "Running build status"
  [sut/build-status {:status :running}])

(defcard-rg build-status-success
  "Successful build status"
  [sut/build-status {:status :success}])

(defcard-rg build-status-error
  "Failed build status"
  [sut/build-status {:status :error}])

(defcard-rg build-details-running
  "Running build details"
  [sut/build-details
   {:start-time "2024-09-24T16:52:00"
    :status :running
    :message "Test build"
    :git {:ref "refs/heads/main"}}])

(defcard-rg build-details-success
  "Successful build details"
  [sut/build-details build-success])

(defcard-rg build-overview-running
  "Running build overview"
  [sut/overview build-running])

(defcard-rg build-overview-success
  "Successful build overview"
  [sut/overview build-success])
