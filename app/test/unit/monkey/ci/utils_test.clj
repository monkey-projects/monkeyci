(ns monkey.ci.utils-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [manifold.deferred :as md]
            [monkey.ci.test.helpers :as h]
            [monkey.ci.utils :as sut]))

(deftest abs-path
  (testing "returns abs path as is"
    (is (= "/abs" (sut/abs-path "/parent" "/abs"))))

  (testing "returns child if nil parent"
    (is (= "child" (sut/abs-path nil "child"))))

  (testing "returns subpath of parent if child is not absolute"
    (is (= "parent/child" (sut/abs-path "parent" "child")))))

(deftest rebase-path
  (testing "makes path relative to last arg"
    (is (= "/dest/sub"
           (sut/rebase-path "/src/sub" "/src" "/dest"))))

  (testing "when relative dir given, adds it to last arg"
    (is (= "/dest/sub"
           (sut/rebase-path "sub" "/src" "/dest")))))

(deftest add-shutdown-hook!
  (testing "registers thread as shutdown hook"
    (let [t (sut/add-shutdown-hook! (constantly :ok))]
      ;; We can only verify this by removing it and checking the return value
      (is (true? (.removeShutdownHook (Runtime/getRuntime) t))))))

(deftest delete-dir
  (testing "deletes all files in directory recursively"
    (h/with-tmp-dir dir
      (is (nil? (spit (io/file dir "test.txt") "some test")))
      (is (some? (sut/delete-dir dir)))
      (is (false? (.exists (io/file dir)))))))

(deftest load-privkey
  (testing "returns input if it's already a private key"
    (let [k (reify java.security.PrivateKey)]
      (is (= k (sut/load-privkey k))))))

(deftest stack-trace
  (testing "prints to string"
    (is (string? (sut/stack-trace (Exception. "Test exception"))))))

(deftest prune-tree
  (testing "removes nil values"
    (is (empty? (sut/prune-tree {:key nil}))))

  (testing "removes nil values from sub"
    (is (= {:parent {:child {:name "test"}}}
           (sut/prune-tree {:parent {:child {:name "test"
                                             :other nil}}}))))

  (testing "removes empty sub maps"
    (is (= {:parent {:child {:name "test"}}}
           (sut/prune-tree {:parent {:child {:name "test"
                                             :other {}}}})))))

(deftest round-up
  (testing "int to int"
    (is (= 10 (sut/round-up 10M))))

  (testing "rounds to next integer"
    (is (= 5 (sut/round-up 4.3M)))))

(deftest file-hash
  (testing "calculates hash string for file"
    (is (string? (sut/file-hash "deps.edn")))))

(deftest update-nth
  (testing "applies f to nth item in collection"
    (is (= ["a" "B" "c"]
           (sut/update-nth ["a" "b" "c"] 1 (memfn toUpperCase))))))

(deftest wait-until
  (let [r (atom nil)
        s (sut/wait-until #(deref r))]
    (testing "returns first non-falsey value of predicate"
      (is (md/deferred? s))
      (is (not (md/realized? s)))
      (is (some? (reset! r ::done)))
      (is (= ::done (deref s 100 :timeout))))))
