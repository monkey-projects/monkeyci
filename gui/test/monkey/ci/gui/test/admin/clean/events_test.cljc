(ns monkey.ci.gui.test.admin.clean.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.admin.clean.events :as sut]
            [monkey.ci.gui.admin.clean.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest clean
  (testing "sends reaper request to backend"
    (rf-test/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:admin-reaper
                              {:status 200
                               :body []
                               :error-code :no-error}})
       (rf/dispatch [::sut/clean])
       (is (= 1 (count @c)))))))

(deftest clean--success
  (testing "sets result in db"
    (rf/dispatch-sync [::sut/clean--success {:body ::results}])
    (is (= ::results (db/get-cleaned-processes @app-db)))))

(deftest clean--failed
  (testing "sets error alert in db"
    (rf/dispatch-sync [::sut/clean--failed "test-error"])
    (is (= :danger (-> (db/get-alerts @app-db)
                       first
                       :type)))))
