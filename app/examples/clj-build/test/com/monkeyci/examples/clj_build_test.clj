(ns com.monkeyci.examples.clj-build-test
  (:require [com.monkeyci.examples.clj-build :as sut]
            [clojure.test :refer [deftest testing is]]))

(deftest main-fn
  (testing "returns something"
    (is (some? (sut/main-fn)))))
