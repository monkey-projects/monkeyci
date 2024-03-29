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

(deftest logs
  (let [l (rf/subscribe [:build/logs])]
    (testing "exists"
      (is (some? l)))

    (testing "returns logs from db"
      (is (nil? @l))
      (is (map? (reset! app-db (db/set-logs {} ::test-logs))))
      (is (= ::test-logs @l)))))

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
               :status :success
               :logs []}]
             @jobs)))

    (testing "adds logs by job id"
      (is (map? (reset! app-db (-> {}
                                   (db/set-build
                                    {:script
                                     {:jobs {"test-job"
                                             {:id "test-job"}}}})
                                   (db/set-logs
                                    [{:name "test-job/out.txt" :size 100}
                                     {:name "test-job/err.txt" :size 50}])))))
      (is (= [{:name "out.txt" :size 100 :path "test-job/out.txt"}
              {:name "err.txt" :size 50 :path "test-job/err.txt"}]
             (-> @jobs first :logs))))))

(deftest reloading?
  (let [r (rf/subscribe [:build/reloading?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns reloading status from db"
      (is (false? @r))
      (is (map? (reset! app-db (db/set-reloading {}))))
      (is (true? @r)))))

(deftest log-alerts
  (let [a (rf/subscribe [:build/log-alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "holds alerts from db"
      (is (map? (reset! app-db (db/set-log-alerts {} ::test-alerts))))
      (is (= ::test-alerts @a)))))

(deftest current-log
  (let [c (rf/subscribe [:build/current-log])]
    (testing "exists"
      (is (some? c)))

    (testing "holds current log from db"
      (is (map? (reset! app-db (db/set-current-log {} "test-log"))))
      (is (= ["test-log"] @c)))

    (testing "splits text by line breaks"
      (is (map? (reset! app-db (db/set-current-log {} "line 1\nline 2"))))
      (is (= ["line 1" [:br] "line 2"] @c)))))

(deftest downloading?
  (let [c (rf/subscribe [:build/downloading?])]
    (testing "exists"
      (is (some? c)))

    (testing "holds current log from db"
      (is (map? (reset! app-db (db/mark-downloading {}))))
      (is (true? @c)))))

(deftest log-path
  (let [c (rf/subscribe [:build/log-path])]
    (testing "exists"
      (is (some? c)))

    (testing "holds current log path from db"
      (is (map? (reset! app-db (db/set-log-path {} "test-path"))))
      (is (= "test-path" @c)))))

(deftest global-logs
  (let [g (rf/subscribe [:build/global-logs])]
    (testing "exists"
      (is (some? g)))

    (testing "returns logs without path"
      (reset! app-db (db/set-logs {} [{:name "out.txt"}
                                      {:name "test-job/out.txt"}]))
      (is (= [{:name "out.txt"}]
             @g)))))
