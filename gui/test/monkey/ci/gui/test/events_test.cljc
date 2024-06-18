(ns monkey.ci.gui.test.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.events]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest initialize-db
  (testing "clears db"
    (is (nil? (reset! app-db nil)))
    (rf/dispatch-sync [:initialize-db])
    (is (some? @app-db))))

(deftest load-version
  (testing "sends request to backend"
    (rft/run-test-sync
     (let [c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-version {:status 200
                                            :body "test-version"
                                            :error-code :no-error}})
       (rf/dispatch [:core/load-version])
       (is (= 1 (count @c)))
       (is (= :get-version (-> @c first (nth 2))))))))

(deftest load-version--success
  (testing "sets version in db"
    (rf/dispatch-sync [:core/load-version--success {:body "test-version"}])
    (is (= "test-version" (:version @app-db)))))

(deftest load-version--failed
  (testing "clears version"
    (is (some? (reset! app-db {:version "previous-version"})))
    (rf/dispatch-sync [:core/load-version--failed "test error"])
    (is (nil? (:version @app-db)))))
