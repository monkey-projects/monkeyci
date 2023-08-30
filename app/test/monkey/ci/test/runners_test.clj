(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci
             [process :as p]
             [runners :as sut]]
            [monkey.ci.test.helpers :as h]))

(deftest make-runner
  (testing "creates child runner"
    (is (= sut/child-runner (sut/make-runner {:runner {:type :child}})))
    (is (= sut/child-runner (sut/make-runner {:runner {:type :local}}))))

  (testing "creates child  runner by default"
    (is (= sut/child-runner (sut/make-runner {}))))

  (testing "supports noop runner"
    (let [r (sut/make-runner {:runner {:type :noop}})]
      (is (fn? r))
      (is (= :noop (-> (r {})
                       :runner))))))

(deftest child-runner
  (let [args {:script {:dir "examples/basic-clj"}}]
    (testing "runs script locally in child process, returns result"
      (with-redefs [p/execute! (constantly {:exit 654})]
        (is (map? (sut/child-runner args)))))

    (testing "adds success result on zero exit code"
      (with-redefs [p/execute! (constantly {:exit 0})]
        (is (= :success (-> (sut/child-runner args)
                            :result))))))

  (testing "with workdir"
    (with-redefs [p/execute! (fn [args]
                               {:exit 0
                                :args args})]
      (testing "passes work dir to process"
        (h/with-tmp-dir base
          (is (true? (.mkdir (io/file base "local"))))
          (is (= base (-> (sut/child-runner {:script {:dir "local"
                                                      :workdir base}})
                          :args
                          :work-dir)))))
      
      (testing "combine relative script dir with workdir"
        (h/with-tmp-dir base
          (is (true? (.mkdir (io/file base "local"))))
          (is (= (str base "/local") (-> (sut/child-runner {:script {:dir "local"
                                                                     :workdir base}})
                                         :args
                                         :script-dir)))))

      (testing "leave absolute script dir as is"
        (h/with-tmp-dir base
          (is (= base (-> (sut/child-runner {:script {:dir base}})
                          :args
                          :script-dir)))))))

  (testing "passes pipeline to process"
    (with-redefs [p/execute! (fn [args]
                               {:exit 0
                                :args args})]
      (h/with-tmp-dir base
        (is (= "test-pipeline" (-> (sut/child-runner {:script {:dir base
                                                               :pipeline "test-pipeline"}})
                                   :args
                                   :pipeline)))))))
