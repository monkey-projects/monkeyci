(ns monkey.ci.runners.server-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [commands :as cmd]
             [runners :as r]]
            [monkey.ci.runners.server :as sut]))

(deftest make-runner
  (let [runner (r/make-runner {:runner {:type :server}})]
    (testing "creates runner fn for `:server` type"
      (is (fn? runner)))

    (testing "invokes run-build command"
      (with-redefs [cmd/run-build identity]
        (let [args (runner {:build-id "test-build"}
                           {:config {:work-dir "/work/dir"}})]
          (testing "with local runner"
            (is (= :local (get-in args [:runner :type]))))

          (testing "sets build checkout dir"
            (is (= "/work/dir/test-build" (get-in args [:build :git :dir])))))))))
