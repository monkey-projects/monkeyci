(ns monkey.ci.gui.test.table-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.table :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest pagination-info-sub
  (let [s (rf/subscribe [:pagination/info ::test-id])]

    (testing "exists"
      (is (some? s)))

    (testing "returns pagination info from db for given id"
      (let [p {:count 5
               :current 2}]
        (is (nil? @s))
        (reset! app-db (sut/set-pagination {} ::test-id p))
        (is (= p @s))))))

(deftest pagination-set-evt
  (testing "sets pagination info in db"
    (let [id ::test-id
          p {:count 5 :current 0}]
      (rf/dispatch-sync [:pagination/set id p])
      (is (= p (sut/get-pagination @app-db id))))))

(deftest pagination-next-evt
  (testing "increases current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 5}])
      (rf/dispatch-sync [:pagination/next id])
      (is (= 2 (:current (sut/get-pagination @app-db id))))))

  (testing "does not increase past page count"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:count 2
                                             :current 1}])
      (rf/dispatch-sync [:pagination/next id])
      (is (= 1 (:current (sut/get-pagination @app-db id)))))))

(deftest pagination-prev-evt
  (testing "decreases current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 3}])
      (rf/dispatch-sync [:pagination/prev id])
      (is (= 0 (:current (sut/get-pagination @app-db id))))))

  (testing "does not decrease below zero"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 0 :count 3}])
      (rf/dispatch-sync [:pagination/prev id])
      (is (= 0 (:current (sut/get-pagination @app-db id)))))))

(deftest pagination-goto-evt
  (testing "sets current page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 10}])
      (rf/dispatch-sync [:pagination/goto id 4])
      (is (= 4 (:current (sut/get-pagination @app-db id))))))

  (testing "does not set invalid page"
    (let [id ::test-id]
      (rf/dispatch-sync [:pagination/set id {:current 1 :count 3}])
      (rf/dispatch-sync [:pagination/goto id 4])
      (is (= 1 (:current (sut/get-pagination @app-db id)))))))
