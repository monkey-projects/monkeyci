(ns monkey.ci.gui.test.build.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest build-load-logs
  (testing "sets alert"
    (rf/dispatch-sync [:build/load-logs])
    (is (= 1 (count (db/alerts @app-db)))))

  (testing "fetches builds from backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (reset! app-db {:route/current
                       {:parameters
                        {:path 
                         {:customer-id "test-cust"
                          :project-id "test-proj"
                          :repo-id "test-repo"
                          :build-id "test-build"}}}})
       (h/initialize-martian {:get-build-logs {:body ["test-log"]
                                               :error-code :no-error}})
       (rf/dispatch [:build/load-logs])
       (is (= 1 (count @c)))
       (is (= :get-build-logs (-> @c first (nth 2)))))))

  (testing "clears current logs"
    (is (map? (reset! app-db (db/set-logs {} ["test-log"]))))
    (rf/dispatch-sync [:build/load-logs])
    (is (nil? (db/logs @app-db)))))

(deftest build-load-logs--success
  (testing "clears alerts"
    (is (map? (reset! app-db (db/set-alerts {} [{:type :info
                                                 :message "test notification"}]))))
    (rf/dispatch-sync [:build/load-logs--success {:body []}])
    (is (nil? (db/alerts @app-db)))))

(deftest build-load-logs--failed
  (testing "sets error"
    (rf/dispatch-sync [:build/load-logs--failed "test-error"])
    (is (= :danger (-> (db/alerts @app-db)
                       (first)
                       :type)))))
