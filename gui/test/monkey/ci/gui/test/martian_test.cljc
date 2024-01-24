(ns monkey.ci.gui.test.martian-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.martian :as sut]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(deftest init
  (testing "initializes default martian in db"
    (sut/init)
    (let [m (get-in @app-db [:martian.re-frame/martian :martian.re-frame/default-id :m])]
      (is (some? m))
      (is (not-empty (:handlers m)))
      (is (= (set (map :route-name (:handlers m)))
             (set (map :route-name sut/routes)))))))

(deftest secure-request
  (testing "dispatches martian request"
    (let [e (h/catch-fx :dispatch)]
      (rf/dispatch-sync [:secure-request :test-evt {:key "value"} ::on-success ::on-failure])
      (is (= 1 (count @e)))
      (is (= [:martian.re-frame/request :test-evt {:key "value"} ::on-success ::on-failure]))))

  (testing "adds auth token as authorization header"
    (let [e (h/catch-fx :dispatch)]
      (is (map? (reset! app-db {:auth/token "test-token"})))
      (rf/dispatch-sync [:secure-request :test-evt {}])
      (is (= {:authorization "Bearer test-token"}
             (-> @e first (nth 2)))))))
