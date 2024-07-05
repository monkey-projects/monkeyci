(ns monkey.ci.gui.test.params.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.params.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest customer-load-params
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [params [{:parameters [{:name "test-param" :value "test-val"}]}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-params {:status 200
                                                    :body params
                                                    :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:params/load "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-params (-> @c first (nth 2)))))))
  
  (testing "marks loading"
    (rf/dispatch-sync [:params/load "test-cust"])
    (is (true? (db/loading? @app-db))))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:params/load "test-cust"])
    (is (nil? (db/alerts @app-db)))))

(deftest load-params--success
  
  (testing "sets params in db"
    (rf/dispatch-sync [:params/load--success {:body ::test-params}])
    (is (= ::test-params (db/params @app-db))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--success {:body ::test-params}])
    (is (not (db/loading? @app-db)))))

(deftest load-params--failed
  (testing "sets alert in db"
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type))))

  (testing "unmarks loading"
    (is (some? (reset! app-db (db/mark-loading {}))))
    (rf/dispatch-sync [:params/load--failed "test error"])
    (is (not (db/loading? @app-db)))))
