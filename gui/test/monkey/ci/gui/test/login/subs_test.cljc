(ns monkey.ci.gui.test.login.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.login.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest submitting?
  (let [s (rf/subscribe [:login/submitting?])]
    (testing "exists"
      (is (some? s)))

    (testing "returns state from db"
      (is (false? @s))
      (is (map? (reset! app-db (db/set-submitting {}))))
      (is (true? @s)))))

(deftest user
  (let [s (rf/subscribe [:login/user])]
    (testing "exists"
      (is (some? s)))

    (testing "returns user from db"
      (is (nil? @s))
      (is (map? (reset! app-db (db/set-user {} "test-user"))))
      (is (= "test-user" @s)))))
