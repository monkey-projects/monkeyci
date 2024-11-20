(ns monkey.ci.gui.test.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.subs :as sut]
            [monkey.ci.gui.test.fixtures :as tf]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(rf/clear-subscription-cache!)

(deftest version
  (let [v (rf/subscribe [:version])]
    (testing "exists"
      (is (some? v)))

    (testing "returns version from db"
      (is (nil? @v))
      (is (some? (reset! app-db {:version "test-version"})))
      (is (= "test-version" @v)))))
