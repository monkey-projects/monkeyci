(ns monkey.ci.test.script-test
  (:require [clojure.test :refer :all]
            [monkey.ci.script :as sut]
            [monkey.ci.build.core :as bc]))

(deftest exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! "examples/basic-clj"))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! "examples/basic-script")))))

(deftest run-pipelines
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-pipelines []))))

  (testing "success if all steps succeed"
    (is (bc/success? (-> [(bc/pipeline {:steps [(constantly bc/success)]})]
                         (sut/run-pipelines)))))

  (testing "runs a single pipline"
    (is (bc/success? (-> (bc/pipeline {:name "single"
                                       :steps [(constantly bc/success)]})
                         (sut/run-pipelines)))))
  
  (testing "fails if a step fails"
    (is (bc/failed? (-> [(bc/pipeline {:steps [(constantly bc/failure)]})]
                        (sut/run-pipelines))))))
