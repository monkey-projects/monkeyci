(ns monkey.ci.events.mailman.script-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman.script :as sut]))

(deftest routes
  (let [types [:script/start
               :job/queued
               :job/executed
               :job/end]
        routes (->> (sut/make-routes {})
                    (into {}))]
    (doseq [t types]
      (testing (format "handles %s event type" t)
        (is (contains? routes t))))))

(deftest script-start
  (let [jobs [{:id ::start}
              {:id ::next
               :dependencies [::start]}]
        evt {:type :script/start
             :script {:jobs jobs}}]
    (testing "queues all pending jobs without dependencies"
      (is (= [(first jobs)]
             (-> {:event evt}
                 (sut/script-start)
                 (sut/get-queued))))))

  (testing "returns `script/end` when no jobs"
    (let [r (sut/script-start {:event {:script {:jobs []}}})]
      (is (empty? (sut/get-queued r)))
      (is (= [:script/end] (->> (sut/get-events r)
                                (map :type))))
      (is (bc/failed? (first (sut/get-events r)))))))

(deftest job-end
  (testing "queues pending jobs with completed dependencies")

  (testing "returns `script/end` when no more jobs to run"))
