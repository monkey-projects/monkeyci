(ns monkey.ci.gui.test.job.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as bdb]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.job.subs :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
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

    (testing "returns alerts from db"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest job-logs
  (let [s (rf/subscribe [:job/logs])]
    (testing "exists"
      (is (some? s)))

    (testing "converts loki format"
      (let [loki {:status "success"
                  :data
                  {:resultType "streams"
                   :result
                   [{:stream
                     {:filename "/var/log/test.log"}
                     :values
                     ;; First entry is timestamp in epoch nanos, second the log line
                     [["100" "Line 1"]
                      ["200" "Line 2"]]}]}}]
        (is (some? (reset! app-db (db/set-logs {} loki))))
        (is (= [{:file "test.log"
                 :contents ["Line 1"
                            "Line 2"]}]
               @s))))))
