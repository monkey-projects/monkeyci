(ns monkey.ci.gui.test.job.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as bdb]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.job.subs :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(rf/clear-subscription-cache!)

(deftest job-current
  (let [s (rf/subscribe [:job/current])]
    (testing "exists"
      (is (some? s)))

    (testing "returns job with id from current route"
      (is (nil? @s))
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path
                                       {:job-id "test-job"}}})
                                    (bdb/set-build
                                     {:id "test-build"
                                      :script
                                      {:jobs {"first"
                                              {:id "first"}
                                              "test-job"
                                              {:id "test-job"}}}})))))
      (is (= {:id "test-job"} @s)))))

(deftest job-alerts
  (let [a (rf/subscribe [:job/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns global alerts from db"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest job-log-files
  (let [f (rf/subscribe [:job/log-files])]
    (testing "exists"
      (is (some? f)))

    (testing "returns files from db"
      (is (nil? @f))
      (is (some? (reset! app-db (db/set-log-files {} ::files))))
      (is (= ::files @f)))))

(deftest job-logs
  (let [path "test.txt"
        s (rf/subscribe [:job/logs path])]
    (testing "exists"
      (is (some? s)))

    (testing "converts loki format"
      (let [loki {:status "success"
                  :src :loki
                  :data
                  {:resultType "streams"
                   :result
                   [{:stream
                     {:filename "/var/log/test.log"}
                     :values
                     ;; First entry is timestamp in epoch nanos, second the log line
                     [["100" "Line 1"]
                      ["200" "Line 2"]]}]}}]
        (is (some? (reset! app-db (db/set-logs {} path loki))))
        (is (= ["Line 1"
                [:br]
                "Line 2"]
               @s))))

    (testing "converts log ingest format"
      (let [data {:status "success"
                  :src :log-ingest
                  :entries
                  [{:ts 100
                    :contents "Line 1\n"}
                   {:ts 200
                    :contents "Line 2\n"}]}]
        (is (some? (reset! app-db (db/set-logs {} path data))))
        (is (= ["Line 1"
                [:br]
                "Line 2"]
               @s))))))

(deftest job-path-alerts
  (let [path "test/path"
        a (rf/subscribe [:job/path-alerts path])]
    (testing "exists"
      (is (some? a)))

    (testing "returns path-specific alerts from db"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} path ::alerts))))
      (is (= ::alerts @a)))))

(deftest job-test-cases
  (let [tc (rf/subscribe [:job/test-cases])]
    (testing "exists"
      (is (some? tc)))

    (testing "returns all tests cases from all suites"
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path
                                       {:job-id "test-job"}}})
                                    (bdb/set-build
                                     {:script
                                      {:jobs
                                       {"test-job"
                                        {:id "test-job"
                                         :result {:monkey.ci/tests
                                                  [{:name "test suite"
                                                    :test-cases [{:name "case 1"}]}
                                                   {:name "other suite"
                                                    :test-cases [{:name "case 2"}]}]}}}}})))))
      (is (= #{{:name "case 1"}
               {:name "case 2"}}
             (set @tc))))

    (testing "returns test cases if no suites"
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path
                                       {:job-id "test-job"}}})
                                    (bdb/set-build
                                     {:script
                                      {:jobs
                                       {"test-job"
                                        {:id "test-job"
                                         :result {:monkey.ci/tests
                                                  {:test-cases [{:name "case 1"}
                                                                {:name "case 2"}]}}}}}})))))
      (is (= #{{:name "case 1"}
               {:name "case 2"}}
             (set @tc))))
    

    (testing "sorts with failed tests first"
      (let [tests [{:name "unit tests"
                    :test-cases
                    [{:name "success"}
                     {:name "failed"
                      :failures [{:message "some failure"}]}
                     {:name "errored"
                      :errors [{:message "some error"}]}]}]
            job-id "failed-job"]
        (is (some? (reset! app-db (-> {}
                                      (r/set-current {:parameters {:path {:job-id job-id}}})
                                      (bdb/set-build
                                       {:script
                                        {:jobs
                                         {job-id
                                          {:id job-id
                                           :result {:monkey.ci/tests tests}}}}})))))
        (is (= ["errored"
                "failed"
                "success"]
               (map :name @tc)))))))

(deftest job-script-with-logs
  (let [l (rf/subscribe [:job/script-with-logs])]
    (testing "exists"
      (is (some? l)))

    (testing "returns script list with log paths"
      (is (empty? @l))
      (is (some? (reset! app-db
                         (-> {}
                             (db/set-log-files
                              ["/var/log/0_out.log"
                               "/var/log/0_err.log"
                               "/var/log/1_out.log"])
                             (r/set-current
                              {:parameters
                               {:path
                                {:job-id "test-job"}}})
                             (bdb/set-build
                              {:id "test-build"
                               :script
                               {:jobs {"test-job"
                                       {:id "test-job"
                                        :script
                                        ["script line 1"
                                         "script line 2"]}}}})))))
      (is (= [{:cmd "script line 1"
               :out "/var/log/0_out.log"
               :err "/var/log/0_err.log"
               :log-src :loki}
              {:cmd "script line 2"
               :out "/var/log/1_out.log"
               :log-src :loki}]
             @l)))

    (testing "includes expanded state"
      (is (some? (swap! app-db (fn [db]
                                 (db/set-log-expanded db 1 true)))))
      (is (true? (-> @l second :expanded?))))))

(deftest script-with-implied-logs
  (let [l (rf/subscribe [:job/script-with-implied-logs])]
    (testing "exists"
      (is (some? l)))

    (testing "empty when no job"
      (is (empty? @l)))
    
    (is (some? (reset! app-db
                       (-> {}
                           (r/set-current
                            {:parameters
                             {:path
                              {:job-id "test-job"}}})
                           (bdb/set-build
                            {:id "test-build"
                             :script
                             {:jobs {"test-job"
                                     {:id "test-job"
                                      :script
                                      ["script line 1"
                                       "script line 2"]}}}})))))

    (testing "when logs present, returns as-is"
      (is (some? (swap! app-db
                        (fn [db]
                          (db/set-log-files db
                           ["/var/log/0_out.log"
                            "/var/log/0_err.log"
                            "/var/log/1_out.log"])))))
      (is (= [{:cmd "script line 1"
               :out "/var/log/0_out.log"
               :err "/var/log/0_err.log"
               :log-src :loki}
              {:cmd "script line 2"
               :out "/var/log/1_out.log"
               :log-src :loki}]
             @l)))

    (testing "when no logs present, adds implied and marks src"
      (is (some? (swap! app-db
                        (fn [db]
                          (db/set-log-files db nil)))))
      (is (= [{:cmd "script line 1"
               :out "0_out.log"
               :err "0_err.log"
               :log-src :log-ingest}
              {:cmd "script line 2"
               :out "1_out.log"
               :err "1_err.log"
               :log-src :log-ingest}]
             @l)))))

(deftest unblocking?
  (h/verify-sub [::sut/unblocking?] db/set-unblocking true false))

(deftest wrap-logs?
  (h/verify-sub [::sut/wrap-logs?] #(db/set-wrap-logs % true) true false))
