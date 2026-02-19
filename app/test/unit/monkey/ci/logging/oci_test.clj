(ns monkey.ci.logging.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci.logging :as l]
            [monkey.ci.logging.oci :as sut]
            [monkey.oci.os.core :as os]))

(deftest oci-bucket-log-retriever
  (testing "`list-logs`"
    (testing "lists all files with build sid prefix"
      (let [logger (l/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"}})]
        (with-redefs [os/list-objects (constantly (md/success-deferred
                                                   {:objects [{:name "a/b/c/out.txt"
                                                               :size 100}]}))]
          (is (= [{:name "out.txt" :size 100}]
                 (l/list-logs logger ["a" "b" "c"]))))))

    (testing "prepends configured prefix"
      (let [logger (l/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"
                                                      :prefix "logs"}})]
        (with-redefs [os/list-objects (fn [_ opts]
                                        (if (= "logs/a/b/c/" (:prefix opts))
                                          (md/success-deferred
                                           {:objects [{:name "logs/a/b/c/out.txt"
                                                       :size 100}]})
                                          (md/error-deferred (ex-info "invalid prefix" opts))))]
          (is (= [{:name "out.txt" :size 100}]
                 (l/list-logs logger ["a" "b" "c"])))))))

  (testing "`fetch-log`"
    (testing "fetches log by downloading object"
      (let [logger (l/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"}})
            contents "this is a test object"]
        (with-redefs [os/get-object (constantly (md/success-deferred
                                                 contents))]
          (is (= contents
                 (slurp (l/fetch-log logger ["a" "b" "c"] "out.txt")))))))

    (testing "prepends object name with configured prefix"
      (let [logger (l/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"
                                                      :prefix "logs"}})
            contents "this is a test object"]
        (with-redefs [os/get-object (fn [_ opts]
                                      (if (= "logs/a/b/c/out.txt" (:object-name opts))
                                        (md/success-deferred
                                         contents)
                                        (md/error-deferred
                                         (ex-info "invalid object name" opts))))]
          (is (= contents
                 (slurp (l/fetch-log logger ["a" "b" "c"] "out.txt")))))))))
