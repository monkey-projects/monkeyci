(ns monkey.ci.gui.test.job.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.job.events :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(deftest job-init
  (letfn [(mock-handlers []
            (rf/reg-event-db
             :customer/maybe-load
             (fn [db _] (assoc db ::customer ::loaded)))
            (rf/reg-event-db
             :build/maybe-load
             (fn [db _] (assoc db ::build ::loaded))))]
    
    (testing "loads customer if not loaded"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::customer @app-db)))))
    
    (testing "loads build details if not present in db"
      (rft/run-test-sync
       (mock-handlers)
       (rf/dispatch [:job/init])
       (is (= ::loaded (::build @app-db)))))))

(deftest job-load-logs
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:customer-id "test-cust"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))
  
  (testing "sends request to logs endpoint with job filter"
    (let [e (h/catch-fx :http-xhrio)]
      (is (nil? (rf/dispatch-sync [:job/load-logs {:id "test-job"
                                                   :start-time 100000
                                                   :end-time 200000}])))
      (is (= 1 (count @e)))
      (is (= "test-cust"
             (-> @e first (get-in [:headers "X-Scope-OrgID"])))
          "passes customer in scope org header")))

  (testing "sets alert"
    (rf/dispatch-sync [:job/load-logs {:id "test-job" :start-time 100}])
    (let [a (db/alerts @app-db)]
      (is (= 1 (count a)))
      (is (= :info (-> a first :type))))))

(deftest job-load-logs--success
  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:job/load-logs--success {}])
    (is (nil? (db/alerts @app-db))))
  
  (testing "sets log lines in db"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-logs--success ::logs])
    (is (= ::logs (db/logs @app-db)))))

(deftest job-load-logs--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:job/load-logs--failed {:message "test error"}])
    (is (= :danger (-> (db/alerts @app-db)
                       first
                       :type)))))
