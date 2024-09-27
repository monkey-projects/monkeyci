(ns monkey.ci.gui.test.job.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [clojure.string :as cs]
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.job.events :as sut]
            [monkey.ci.gui.login.db :as ldb]
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

(deftest job-leave
  (testing "clears all job info from db"
    (let [uid (repeatedly 4 (comp str random-uuid))]
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path (zipmap [:customer-id :repo-id :build-id :job-id]
                                                     uid)}})
                                    (db/set-alerts [{:type :info :message "test alert"}])))))
      (rf/dispatch-sync [:job/leave uid])
      (is (nil? (db/get-alerts @app-db))))))

(deftest job-load-log-files
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:customer-id "test-cust"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))
  (rft/run-test-sync
   (h/initialize-martian {:get-log-label-values {:error-code :no-error
                                                 :body {}}})
   
   (testing "sends request to label values endpoint with job filter"
     (let [e (h/catch-fx :martian.re-frame/request)]
       (is (nil? (rf/dispatch [:job/load-log-files
                               {:id "test-job"
                                :start-time 100000
                                :end-time 200000}])))
       (is (= 1 (count @e)))
       (let [params (-> @e
                        first
                        (nth 3))]
         (is (= "filename" (:label params)))
         (is (= 100 (:start params)))
         (is (= 201 (:end params))))))))

(deftest job-load-log-files--success
  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} ::test-alerts))))
    (rf/dispatch-sync [:job/load-log-files--success {}])
    (is (nil? (db/global-alerts @app-db))))
  
  (testing "sets log files in db"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-log-files--success {:body {:data ::logs}}])
    (is (= ::logs (db/log-files @app-db)))))

(deftest job-load-log-files--failed
  (testing "sets error alert"
    (rf/dispatch-sync [:job/load-log-files--failed {:message "test error"}])
    (is (= :danger (-> (db/global-alerts @app-db)
                       first
                       :type)))))

(deftest job-load-logs
  (is (some? (reset! app-db (r/set-current {}
                                           {:parameters
                                            {:path
                                             {:customer-id "test-cust"
                                              :repo-id "test-repo"
                                              :build-id "test-build"
                                              :job-id "test-job"}}}))))

  (rft/run-test-sync

   (h/initialize-martian {:download-log {:error-code :no-error
                                         :body {}}})
   
   (let [e (h/catch-fx :martian.re-frame/request)]
     (is (nil? (rf/dispatch [:job/load-logs
                             {:id "test-job"
                              :start-time 100000
                              :end-time 200000}
                             "test/path"])))
     (is (= 1 (count @e)))
     
     (testing "sends request to query range endpoint with job and filename filter"
       (is (cs/includes? (-> @e first (nth 3) :query)
                         "filename=\"test/path\"")))

     (testing "sets customer id in request"
       (is (= "test-cust" (-> @e first (nth 3) :customer-id))))

     (testing "marks loading"
       (is (db/logs-loading? @app-db "test/path"))))))

(deftest job-load-logs--success
  (testing "clears alerts"
    (let [path "test/path"]
      (is (some? (reset! app-db (db/set-alerts {} path ::test-alerts))))
      (rf/dispatch-sync [:job/load-logs--success path {}])
      (is (nil? (db/path-alerts @app-db path)))))
  
  (testing "sets log lines in db"
    (is (empty? (reset! app-db {})))
    (rf/dispatch-sync [:job/load-logs--success "test/path" {:body ::logs}])
    (is (= ::logs (db/get-logs @app-db "test/path")))))

(deftest job-load-logs--failed
  (testing "sets error alert"
    (let [path "error.log"]
      (rf/dispatch-sync [:job/load-logs--failed path {:message "test error"}])
      (is (= :danger (-> (db/path-alerts @app-db path)
                         first
                         :type))))))

