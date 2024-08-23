(ns monkey.ci.containers.build-api-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers :as mcc]
            [monkey.ci.containers.build-api :as sut]
            [monkey.ci.test.aleph-test :as at]))

#_(deftest run-container
  (testing "invokes endpoint on api server"
    (is (some? (mcc/run-container {:containers
                                   {:type :build-api
                                    :url "http://build-api"
                                    :token "test-token"}
                                   :job
                                   {:id "test-job"
                                    :container/image "test-img"
                                    :script ["test" "script"]}})))))
