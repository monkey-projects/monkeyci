(ns monkey.ci.gui.test.repo.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.repo.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest repo-load
  (testing "loads customer if not existing"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-customer-id"])
       (is (= 1 (count @c)))
       (is (= :get-customer (-> @c first (nth 2)))))))

  (testing "does not load customer if already loaded"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db (cdb/set-customer {} {:name "existing customer"}))
       (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                             :error-code :no-error}})
       
       (rf/dispatch [:repo/load "test-customer-id"])
       (is (empty? @c))))))

(deftest builds-load
  (testing "sets alert"
    (rf/dispatch-sync [:builds/load])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :project-id "test-proj"
                          :repo-id "test-repo"}}}})
       (h/initialize-martian {:get-builds {:body [{:id "test-build"}]
                                           :error-code :no-error}})
       (rf/dispatch [:builds/load])
       (is (= 1 (count @c)))
       (is (= :get-builds (-> @c first (nth 2)))))))

  (testing "clears current builds"
    (is (map? (reset! app-db (db/set-builds {} [{:id "initial-build"}]))))
    (rf/dispatch-sync [:builds/load])
    (is (nil? (db/builds @app-db)))))

(deftest build-load-success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:builds/load--success {:body []}])
    (is (nil? (db/alerts @app-db)))))
