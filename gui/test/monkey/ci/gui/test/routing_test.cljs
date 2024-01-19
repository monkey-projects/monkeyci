(ns monkey.ci.gui.test.routing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.routing :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest on-route-change
  (testing "dispatches `route/goto` event"
    (rf-test/run-test-sync
     (is (nil? (sut/on-route-change :test-match :test-history)))
     (is (= :test-match (:route/current @app-db))))))

(deftest current-sub
  (let [c (rf/subscribe [:route/current])]

    (testing "exists"
      (is (some? c)))

    (testing "returns current route from db"
      (is (empty? (reset! app-db {})))
      (is (nil? @c))
      (is (not-empty (reset! app-db {:route/current :test-route})))
      (is (= :test-route @c)))))

(deftest route-goto
  (testing "sets current route to arg"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:route/goto "test-match"])
    (is (= "test-match" (:route/current @app-db)))))

(deftest path-for
  (testing "`nil` if unknown path"
    (is (nil? (sut/path-for :unkown))))

  (testing "returns path string for the route"
    (is (= "/login"
           (sut/path-for :page/login))))

  (testing "sets path parameters"
    (is (= "/c/test-customer"
           (sut/path-for :page/customer {:customer-id "test-customer"}))))

  (testing "sets multiple path parameters"
    (is (= "/c/cust/r/repo"
           (sut/path-for :page/repo {:customer-id "cust"
                                     :repo-id "repo"})))))
