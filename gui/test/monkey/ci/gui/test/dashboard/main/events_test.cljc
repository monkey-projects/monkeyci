(ns monkey.ci.gui.test.dashboard.main.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.dashboard.main.events :as sut]
            [monkey.ci.gui.dashboard.main.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :once f/dashboard-router)
(use-fixtures :each f/reset-db)

(deftest recent-builds
  (rf-test/run-test-sync
   (let [c (h/catch-fx :martian.re-frame/request)]
     (h/initialize-martian {:get-recent-builds {:status 200
                                                :body []
                                                :error-code :no-error}})
     (is (some? (swap! app-db r/set-current (r/match-by-name
                                             :page/org-dashboard
                                             {:org-id "test-org"}))))
     (is (= "test-org" (r/org-id @app-db)))
     
     (is (nil? (rf/dispatch [::sut/recent-builds])))
     
     (testing "fetches recent builds from backend"
       (is (= 1 (count @c)))))))

(deftest recent-builds--success
  (testing "sets result in db"
    (is (nil? (rf/dispatch-sync [::sut/recent-builds--success
                                 {:body ::test-builds}])))
    (is (= ::test-builds (db/get-recent-builds @app-db)))))
