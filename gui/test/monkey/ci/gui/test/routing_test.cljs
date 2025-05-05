(ns monkey.ci.gui.test.routing-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.routing :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest on-route-change
  (testing "dispatches `route/goto` event"
    (let [route {:path "/test"}]
      (rf-test/run-test-sync
       (is (nil? (sut/on-route-change route :test-history)))
       (is (= route (:route/current @app-db)))))))

(deftest current-sub
  (let [c (rf/subscribe [:route/current])]

    (testing "exists"
      (is (some? c)))

    (testing "returns current route from db"
      (is (nil? @c))
      (is (not-empty (reset! app-db {:route/current :test-route})))
      (is (= :test-route @c)))))

(deftest route-changed
  (testing "sets current route to arg"
    (let [route {:path "test-match"}]
      (is (nil? (rf/dispatch-sync [:route/changed route])))
      (is (= route (:route/current @app-db)))))

  (testing "dispatches registered leave handlers"
    (rf-test/run-test-sync
     (is (some? (rf/reg-event-db ::test-evt (fn [db _] (assoc db ::invoked true)))))
     (is (nil? (rf/dispatch [:route/on-page-leave [::test-evt]])))
     (is (nil? (rf/dispatch [:route/changed {:path "/new-page"}])))
     (is (true? (::invoked @app-db)))))

  (testing "clears leave handlers"
    (is (nil? (rf/dispatch-sync [:route/on-page-leave [::test-evt]])))
    (is (some? (sut/on-page-leave @app-db)))
    (is (nil? (rf/dispatch-sync [:route/changed {:path "new-page"}])))
    (is (nil? (sut/on-page-leave @app-db))))

  (testing "does nothing when paths have not changed"
    (rf-test/run-test-sync
     (is (nil? (rf/dispatch [:route/changed {:path "/curr"}])))
     (is (some? (rf/reg-event-db ::test-evt (fn [db _] (assoc db ::invoked true)))))
     (is (nil? (rf/dispatch [:route/on-page-leave [::test-evt]])))
     (is (nil? (rf/dispatch [:route/changed {:path "/curr"}])))
     (is (nil? (::invoked @app-db))))))

(deftest path-for
  (testing "`nil` if unknown path"
    (is (nil? (sut/path-for :unkown))))

  (testing "returns path string for the route"
    (is (= "/login"
           (sut/path-for :page/login))))

  (testing "sets path parameters"
    (is (= "/o/test-customer"
           (sut/path-for :page/customer {:customer-id "test-customer"}))))

  (testing "sets multiple path parameters"
    (is (= "/o/cust/r/repo"
           (sut/path-for :page/repo {:customer-id "cust"
                                     :repo-id "repo"})))))

(deftest route-goto
  ;; Failsave
  (h/catch-fx :route/goto)
  
  (testing "sets browser path using fx"
    (let [c (h/catch-fx :route/goto)]
      (rf/dispatch-sync [:route/goto :page/root])
      (is (= "/" (first @c)))))

  (testing "dispatches `route/changed` event with reitit match"
    (rf-test/run-test-sync
     (rf/dispatch [:route/goto :page/root])
     (is (= :page/root (get-in (:route/current @app-db) [:data :name]))))))
