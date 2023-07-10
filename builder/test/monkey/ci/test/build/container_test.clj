(ns monkey.ci.test.build.container-test
  (:require [clojure.test :refer :all]
            [monkey.ci.build.container :as sut]))

(deftest image
  (testing "adds image to step config"
    (is (= "test-image" (-> {}
                            (sut/image "test-image")
                            :container/image)))))

