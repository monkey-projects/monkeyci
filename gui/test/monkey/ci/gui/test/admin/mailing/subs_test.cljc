(ns monkey.ci.gui.test.admin.mailing.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.admin.mailing.db :as db]
            [monkey.ci.gui.admin.mailing.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.helpers :as h]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest mailing-list
  (let [m [{:id ::test}]]
    (h/verify-sub [::sut/mailing-list] #(db/set-mailings % m) m nil)))

(deftest loading?
  (h/verify-sub [::sut/loading?] #(lo/set-loading % db/mailing-id) true false))

(deftest alerts
  (h/verify-sub [::sut/alerts] #(db/set-alerts % ::test-alerts) ::test-alerts nil))

(deftest mailing-editing
  (h/verify-sub [::sut/editing] #(db/set-editing % ::test-editing) ::test-editing nil))

(deftest edit-alerts
  (h/verify-sub [::sut/edit-alerts] #(db/set-editing-alerts % ::test-alerts) ::test-alerts nil))
