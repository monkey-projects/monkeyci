(ns monkey.ci.gui.test.admin.clean.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.admin.clean.db :as db]
            [monkey.ci.gui.admin.clean.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]))

(deftest clean-results
  (h/verify-sub [::sut/clean-results] #(db/set-cleaned-processes % ::cleaned) ::cleaned nil))

(deftest clean-alerts
  (h/verify-sub [::sut/clean-alerts] #(lo/set-alerts % db/clean ::test-alerts) ::test-alerts nil))

(deftest cleaned?
  (h/verify-sub [::sut/cleaned?] #(lo/set-loaded % db/clean) true false))

(deftest cleaning?
  (h/verify-sub [::sut/cleaning?] #(lo/set-loading % db/clean) true false))
