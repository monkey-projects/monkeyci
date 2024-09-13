(ns monkey.ci.gui.test.tabs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.tabs :as sut]
            [re-frame.core :as rf]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest tab-changed-evt
  (testing "updates current tab id in db"
    (let [id ::test-tab
          tab {:id ::changed}
          c (rf/subscribe [:tab/current id])]
      (is (some? c))
      (is (nil? @c))
      (is (nil? (rf/dispatch-sync [:tab/tab-changed id ::changed])))
      (is (= ::changed @c)))))
