(ns monkey.ci.build.container-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.build
             [container :as sut]
             [spec :as spec]]))

(deftest image
  (testing "adds image to job config"
    (is (= "test-image" (-> {}
                            (sut/image "test-image")
                            :container/image)))))

(deftest image-spec
  (testing "allows valid jobs"
    (is (s/valid? :ci/job {:container/image "test-image"
                           :container/cmd ["test" "cmd"]})))

  (testing "allows mounts"
    (is (s/valid? :ci/job {:container/image "test-image"
                           :container/mounts [["/host/vol" "/container/vol"]]}))))
