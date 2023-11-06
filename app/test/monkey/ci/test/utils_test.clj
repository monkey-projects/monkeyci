(ns monkey.ci.test.utils-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci.utils :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest abs-path
  (testing "returns abs path as is"
    (is (= "/abs" (sut/abs-path "/parent" "/abs"))))

  (testing "returns child if nil parent"
    (is (= "child" (sut/abs-path nil "child"))))

  (testing "returns subpath of parent if child is not absolute"
    (is (= "parent/child" (sut/abs-path "parent" "child")))))

(deftest add-shutdown-hook!
  (testing "registers thread as shutdown hook"
    (let [t (sut/add-shutdown-hook! (constantly :ok))]
      ;; We can only verify this by removing it and checking the return value
      (is (true? (.removeShutdownHook (Runtime/getRuntime) t))))))

(deftest delete-dir
  (testing "deletes all files in directory recursively"
    (h/with-tmp-dir dir
      (is (nil? (spit (io/file dir "test.txt") "some test")))
      (is (nil? (sut/delete-dir dir)))
      (is (false? (.exists (io/file dir)))))))

(deftest future->ch
  (testing "returns a channel that holds the future value"
    (let [p (promise)
          f (future @p)
          c (sut/future->ch f)
          take #(h/try-take c 200 :timeout)]
      (is (= :timeout (take)))
      (is (some? (deliver p :test-value)))
      (is (= :test-value (take))))))

(deftest load-privkey
  (testing "returns input if it's already a private key"
    (let [k (reify java.security.PrivateKey)]
      (is (= k (sut/load-privkey k))))))
