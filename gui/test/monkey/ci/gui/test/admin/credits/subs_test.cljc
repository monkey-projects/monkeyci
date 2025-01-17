(ns monkey.ci.gui.test.admin.credits.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.credits.subs :as sut]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest issues
  (h/verify-sub
   [:credits/issues]
   #(lo/set-value % db/issues [::test-credits])
   [::test-credits]
   nil))

(deftest issues-loading?
  (h/verify-sub
   [:credits/issues-loading?]
   #(lo/set-loading % db/issues)
   true
   false))

(deftest issue-alerts
  (h/verify-sub
   [:credits/issue-alerts]
   #(db/set-issue-alerts % ::test-alerts)
   ::test-alerts
   nil))

(deftest issue-saving?
  (h/verify-sub
   [:credits/issue-saving?]
   db/set-issue-saving
   true
   false))

(deftest show-issue-form?
  (h/verify-sub
   [:credits/show-issue-form?]
   db/show-issue-form
   true
   false))

(deftest credit-subs
  (h/verify-sub
   [:credits/subs]
   #(lo/set-value % db/subscriptions [::test-credits])
   [::test-credits]
   nil))

(deftest subs-loading?
  (h/verify-sub
   [:credits/subs-loading?]
   #(lo/set-loading % db/subscriptions)
   true
   false))

(deftest sub-alerts
  (h/verify-sub
   [:credits/sub-alerts]
   #(db/set-sub-alerts % ::test-alerts)
   ::test-alerts
   nil))

(deftest sub-saving?
  (h/verify-sub
   [:credits/sub-saving?]
   db/set-sub-saving
   true
   false))

(deftest show-sub-form?
  (h/verify-sub
   [:credits/show-sub-form?]
   db/show-sub-form
   true
   false))

(deftest issue-all-alerts
  (h/verify-sub
   [:credits/issue-all-alerts]
   #(db/set-issue-all-alerts % ::test-alerts)
   ::test-alerts
   nil))

(deftest issuing-all?
  (h/verify-sub
   [:credits/issuing-all?]
   db/set-issuing-all
   true
   false))
