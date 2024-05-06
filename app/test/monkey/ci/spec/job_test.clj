(ns monkey.ci.spec.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as sut]))

(defn- validate-spec [spec obj]
  (is (s/valid? spec obj)
      (s/explain-str spec obj)))

(deftest spec-job
  (testing "valid job configurations"
    (let [configs [{:job/id "action-job"
                    :job/action (constantly "ok")
                    :job/dependencies
                    ["other-job"]
                    :job/caches
                    [{:blob/id "mvn-cache"
                      :blob/path "/mvn/cache"}]
                    :job/save-artifacts
                    [{:blob/id "jar"
                      :blob/path "test.jar"}]}
                   {:job/id "container-job"
                    :job/container
                    {:container/image "test:img"
                     :container/commands ["a" "b"]}
                    :job/dependencies
                    ["other-job"]
                    :job/restore-artifacts
                    [{:blob/id "jar"
                      :blob/path "test.jar"}]}
                   {:job/id "completed-job"
                    :job/action (constantly true)}]]
      (doseq [c configs]
        (validate-spec :job/props c))))

  (testing "valid job states"
    (validate-spec :job/status {:job/id "test-job"
                                :job/phase :completed
                                :time/start 100
                                :time/end 200}))

  (testing "invalid job configurations"
    (let [configs [{:job/action (constantly "no id")}
                   {:job/id "no-spec"}]]
      (doseq [c configs]
        (is (not (s/valid? :job/props c)) c))))

  (testing "invalid job states"
    (is (not (s/valid? :job/status {:job/phase :running})))))
