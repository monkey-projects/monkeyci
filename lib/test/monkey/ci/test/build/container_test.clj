(ns monkey.ci.test.build.container-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.build
             [container :as sut]
             [spec :as spec]]))

(deftest image
  (testing "adds image to step config"
    (is (= "test-image" (-> {}
                            (sut/image "test-image")
                            :container/image)))))

(deftest image-spec
  (testing "allows valid steps"
    (is (s/valid? :ci/step {:container/image "test-image"
                            :container/cmd ["test" "cmd"]}))))
