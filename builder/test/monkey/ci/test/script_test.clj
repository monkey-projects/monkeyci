(ns monkey.ci.test.script-test
  (:require [clojure.test :refer :all]
            [monkey.ci.script :as sut]
            [monkey.ci.build.core :as bc]))

(deftest ^:integration exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! "examples/basic-clj"))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! "examples/basic-script")))))

