(ns monkey.ci.build.container-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.build.container :as sut]
            [monkey.ci.spec.job :as spec]))

(deftest image
  (testing "adds image to job config"
    (is (= "test-image" (-> {:type :container}
                            (sut/image "test-image")
                            :container/image)))))

(deftest image-spec
  (testing "allows valid jobs"
    (is (s/valid? ::spec/job {:id "test-job"
                              :type :container
                              :container/image "test-image"
                              :script ["test" "cmd"]}))))
