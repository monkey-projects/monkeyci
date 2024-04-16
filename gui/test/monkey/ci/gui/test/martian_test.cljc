(ns monkey.ci.gui.test.martian-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.martian :as sut]
            [monkey.ci.gui.test.fixtures :as f]
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
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer {:status 200}})
       (rf/dispatch [:secure-request :get-customer {:key "value"} [::on-success] [::on-failure]])
       (is (= 1 (count @e)))
       (is (= :get-customer (-> @e first (nth 2)))))))

  (testing "adds auth token as authorization header"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (is (map? (swap! app-db assoc :auth/token "test-token")))
       (h/initialize-martian {:get-customer {:status 200}})
       (rf/dispatch [:secure-request :get-customer {} [::on-success] [::on-failure]])
       (is (= 1 (count @e)))
       (is (= {:authorization "Bearer test-token"}
              (-> @e first (nth 3))))))))
