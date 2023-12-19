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
    (rft/run-test-async
     (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                           :error-code :no-error}})
     
     (rf/dispatch [:repo/load "test-customer-id"])
     (rft/wait-for
      [:customer/load--success]
      (is (= "test customer" (:name (cdb/customer @app-db))))))))

(deftest repo-load-when-existing
  (testing "does not load customer if already loaded"
    (rft/run-test-async
     (h/initialize-martian {:get-customer {:body {:name "test customer"}
                                           :error-code :no-error}})
     (reset! app-db (cdb/set-customer {} {:name "existing customer"}))
     
     (rf/dispatch [:repo/load "test-customer-id"])
     (rft/wait-for
      [:repo/load
       :customer/load--success]
      (is (= "existing customer" (:name (cdb/customer @app-db))))))))

(deftest build-load
  (testing "sets alert"
    (rf/dispatch-sync [:builds/load])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-async
     (h/initialize-martian {:get-builds {:body [{:id "test-build"}]
                                         :error-code :no-error}})

     (reset! app-db {:route/current
                     {:parameters
                      {:path 
                       {:customer-id "test-cust"
                        :project-id "test-proj"
                        :repo-id "test-repo"}}}})
     (rf/dispatch [:builds/load])
     (rft/wait-for
      [:builds/load--success]
      (is (= "test-build" (-> (db/builds @app-db)
                              (first)
                              :id)))))))

(deftest build-load-success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:builds/load--success {:body []}])
    (is (nil? (db/alerts @app-db)))))
