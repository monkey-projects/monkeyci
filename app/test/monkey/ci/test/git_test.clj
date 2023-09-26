(ns monkey.ci.test.git-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-jgit.porcelain :as git]
            [monkey.ci.git :as sut]))

(deftest clone
  (testing "invokes `git-clone`"
    (with-redefs [git/git-clone (fn [& args]
                                  args)]
      (is (= ["http://url" :branch "master" :dir "tmp"]
             (sut/clone "http://url" "master" "tmp"))))))

(deftest checkout
  (testing "invokes `git-checkout`"
    (with-redefs [git/git-checkout (fn [& args]
                                     args)]
      (is (= [:test-repo {:name "test-id"}]
             (sut/checkout :test-repo "test-id"))))))

(deftest clone+checkout
  (testing "clones, then checks out"
    (with-redefs [sut/clone (constantly :test-repo)
                  sut/checkout (fn [repo id]
                                 {:repo repo
                                  :id id})]
      (is (= :test-repo
             (sut/clone+checkout "http://test-url" "main" "test-id" "test-dir")))))

  (testing "does not check out when no commit id"
    (with-redefs [sut/clone (constantly :test-repo)
                  sut/checkout (fn [& args]
                                 (throw (ex-info "This should not be invoked" {})))]
      (is (= :test-repo
             (sut/clone+checkout "http://test-url" "main" nil "test-dir"))))))
