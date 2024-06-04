(ns monkey.ci.gui.test.home.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.home.subs :as sut]
            [monkey.ci.gui.home.db :as db]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest user-customers
  (let [uc (rf/subscribe [:user/customers])]
    (testing "exists"
      (is (some? uc)))

    (testing "returns user customers"
      (is (nil? @uc))
      (is (some? (reset! app-db (db/set-customers {} ::customers))))
      (is (= ::customers @uc)))))
