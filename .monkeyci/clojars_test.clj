(ns clojars-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojars :as sut]))

(deftest latest-version
  (testing "retrieves latest version number from clojars api"
    (is (string? (sut/latest-version "com.monkeyci" "test")))))

(deftest extract-lib
  (testing "reads group and artifact from `deps.edn` file"
    (is (= ["com.monkeyci" "app"]
           (sut/extract-lib "../app/deps.edn")))))
