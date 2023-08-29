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
      (is (= :noop (r {}))))))

(deftest child-runner
  (testing "runs script locally in child process, returns exit code"
    (with-redefs [p/execute! (constantly {:exit ::ok})]
      (is (= ::ok (sut/child-runner {:script {:dir "examples/basic-clj"}})))))

  (testing "with workdir"
    (with-redefs [p/execute! (partial hash-map :exit)]
      (testing "passes work dir to process"
        (h/with-tmp-dir base
          (is (true? (.mkdir (io/file base "local"))))
          (is (= base (-> (sut/child-runner {:script {:dir "local"
                                                      :workdir base}})
                          :work-dir)))))
      
      (testing "combine relative script dir with workdir"
        (h/with-tmp-dir base
          (is (true? (.mkdir (io/file base "local"))))
          (is (= (str base "/local") (-> (sut/child-runner {:script {:dir "local"
                                                                     :workdir base}})
                                         :script-dir)))))

      (testing "leave absolute script dir as is"
        (h/with-tmp-dir base
          (is (= base (-> (sut/child-runner {:script {:dir base}})
                          :script-dir))))))))
