(ns monkey.ci.gui.test.billing.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.billing.db :as db]
            [monkey.ci.gui.billing.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest billing-alerts
  (h/verify-sub [::sut/billing-alerts] #(db/set-billing-alerts % ::alerts) ::alerts nil))

(deftest billing-loading?
  (h/verify-sub [::sut/billing-loading?] db/set-billing-loading true false))

(deftest invoicing-settings
  (h/verify-sub [::sut/invoicing-settings] #(db/set-invoicing-settings % ::settings) ::settings nil))

(deftest saving?
  (h/verify-sub [::sut/saving?] db/set-saving true false))
