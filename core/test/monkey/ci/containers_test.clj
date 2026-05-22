(ns monkey.ci.containers-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.containers :as sut]))

(deftest image-test
  (testing "returns :container/image when present"
    (is (= "test-img" (sut/image {:container/image "test-img"}))))
  (testing "falls back to :image"
    (is (= "legacy-img" (sut/image {:image "legacy-img"}))))
  (testing "prefers :container/image over :image"
    (is (= "new" (sut/image {:container/image "new" :image "old"})))))

(deftest env-test
  (testing "returns :container/env"
    (is (= {"FOO" "bar"} (sut/env {:container/env {"FOO" "bar"}})))))

(deftest cmd-test
  (testing "returns :container/cmd"
    (is (= ["sh" "-c" "echo hi"] (sut/cmd {:container/cmd ["sh" "-c" "echo hi"]})))))

(deftest mounts-test
  (testing "returns :container/mounts"
    (is (= {"/host" "/container"} (sut/mounts {:container/mounts {"/host" "/container"}})))))

(deftest entrypoint-test
  (testing "returns :container/entrypoint"
    (is (= ["/bin/sh"] (sut/entrypoint {:container/entrypoint ["/bin/sh"]})))))

(deftest arch-test
  (testing "returns :arch"
    (is (= :arm (sut/arch {:arch :arm})))))

(deftest props-test
  (testing "is a collection of property keys"
    (is (coll? sut/props))
    (is (seq sut/props))))
