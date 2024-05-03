(ns monkey.ci.spec.job-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.job :as sut]))

(defn- validate-spec [spec obj]
  (is (s/valid? spec obj)
      (s/explain-str spec obj)))

(deftest spec-job
  (testing "valid job configurations"
    (let [configs [{:id "action-job"
                    :spec {:action (constantly "ok")
                           :dependencies
                           ["other-job"]
                           :caches
                           [{:id "mvn-cache"
                             :path "/mvn/cache"}]
                           :save-artifacts
                           [{:id "jar"
                             :path "test.jar"}]}}
                   {:id "container-job"
                    :spec {:container
                           {:image "test:img"
                            :commands ["a" "b"]}
                           :dependencies
                           ["other-job"]
                           :restore-artifacts
                           [{:id "jar"
                             :path "test.jar"}]}}
                   {:id "completed-job"
                    :spec {:action (constantly true)}
                    :status
                    {:phase :completed}}]]
      (doseq [c configs]
        (validate-spec :script/job c))))

  (testing "invalid job configurations"
    (let [configs [{:spec {:action "no id"}}
                   {:id "action-and-container"
                    :spec
                    {:action (constantly :ok)
                     :container {:image "test-img"}}}]]
      (doseq [c configs]
        (is (not (s/valid? :script/job c)) c)))))
