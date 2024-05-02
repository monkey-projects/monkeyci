(ns monkey.ci.gui.test.build.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.subs :as sut]
            [monkey.ci.gui.repo.db :as rdb]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest alerts
  (let [a (rf/subscribe [:build/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "holds alerts from db"
      (is (map? (reset! app-db (db/set-alerts {} ::test-alerts))))
      (is (= ::test-alerts @a)))))

(deftest jobs
  (let [jobs (rf/subscribe [:build/jobs])]
    (testing "exists"
      (is (some? jobs)))

    (testing "returns build jobs as list"
      (is (some? (reset! app-db (db/set-build {} {:script
                                                  {:jobs {"test-job"
                                                          {:id "test-job"
                                                           :status :success}}}}))))
      (is (= [{:id "test-job"
               :status :success}]
             @jobs)))))

(deftest reloading?
  (let [r (rf/subscribe [:build/reloading?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns reloading status from db"
      (is (false? @r))
      (is (map? (reset! app-db (db/set-reloading {}))))
      (is (true? @r)))))

(deftest expanded-jobs
  (let [c (rf/subscribe [:build/expanded-jobs])]
    (testing "exists"
      (is (some? c)))

    (testing "returns expanded job ids from db"
      (is (map? (reset! app-db (db/set-expanded-jobs {} [::job-1 ::job-2]))))
      (is (= #{::job-1 ::job-2} @c)))))
