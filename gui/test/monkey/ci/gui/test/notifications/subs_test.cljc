(ns monkey.ci.gui.test.notifications.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.notifications.db :as db]
            [monkey.ci.gui.notifications.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest unregistering?
  (h/verify-sub [::sut/unregistering?] db/set-unregistering true false))

(deftest alerts
  (h/verify-sub [::sut/alerts] #(db/set-alerts % ::test-alert) ::test-alert nil))

