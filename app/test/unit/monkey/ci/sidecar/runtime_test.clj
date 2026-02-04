(ns monkey.ci.sidecar.runtime-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.sidecar
             [config :as sc]
             [runtime :as sut]
             [spec :as ss]]))

(def config
  (-> {}
      (sc/set-poll-interval 500)
      (sc/set-events-file "test-events")
      (sc/set-abort-file "test-abort")
      (sc/set-start-file "test-start")
      (sc/set-api {:url "http://test-api" :token "test-token"})
      (sc/set-workspace {:type :disk
                         :dir "test-dir"})
      (sc/set-job {:id "test-job"})
      (sc/set-sid ["test-cust" "test-repo" "test-build"])))

(deftest make-system
  (testing "creates system map with runtime"
    (is (some? (:runtime (sut/make-system config))))))

(deftest with-runtime
  (testing "passes runtime component to arg"
    (let [res (sut/with-runtime config identity)]
      (is (sut/runtime? res))
      (is (spec/valid? ::ss/runtime res)
          (spec/explain-str ::ss/runtime res)))))
