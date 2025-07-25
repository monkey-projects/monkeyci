(ns monkey.ci.gui.test.webhooks.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.loader :as l]
            [monkey.ci.gui.webhooks.db :as db]
            [monkey.ci.gui.webhooks.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest repo-webhooks
  (h/verify-sub
   [:repo/webhooks]
   #(db/set-webhooks % ::test-webhooks)
   ::test-webhooks
   nil))

(deftest webhooks-alerts
  (h/verify-sub
   [:webhooks/alerts]
   #(db/set-alerts % ::test-alerts)
   ::test-alerts
   nil))

(deftest webhooks-loading?
  (h/verify-sub
   [:webhooks/loading?]
   #(l/set-loading % db/id)
   true
   false))

(deftest webhooks-new
  (h/verify-sub
   [:webhooks/new]
   #(db/set-new % ::new)
   ::new
   nil))
