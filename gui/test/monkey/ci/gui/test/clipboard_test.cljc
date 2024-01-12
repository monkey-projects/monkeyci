(ns monkey.ci.gui.test.clipboard-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is  use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.clipboard :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest clipboard-copy
  (testing "renders icon"
    (is (vector? (sut/clipboard-copy "test" "some description")))))

(deftest current-sub
  (let [s (rf/subscribe [::sut/current])]
    (testing "exists"
      (is (some? s)))

    (testing "returns current value"
      (is (nil? @s))
      (is (some? (reset! app-db {::sut/current "test-val"})))
      (is (= "test-val" @s)))))

(deftest clipboard-copy-evt
  (testing "updates value in db"
    (h/catch-fx :clipboard/set) ; Avoid overwriting the user's clipboard
    (is (nil? (::sut/current @app-db)))
    (rf/dispatch-sync [::sut/clipboard-copy "new value"])
    (is (= "new value" (::sut/current @app-db))))

  (testing "triggers clipboard/set fx"
    (let [f (h/catch-fx :clipboard/set)]
      (rf/dispatch-sync [::sut/clipboard-copy "new value"])
      (is (= "new value" (first @f))))))
