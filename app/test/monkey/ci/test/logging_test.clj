(ns monkey.ci.test.logging-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as sut]
             [oci :as oci]]
            [monkey.ci.test.helpers :as h]))

(def file? (partial instance? java.io.File))

(deftest make-logger
  (testing "type `inherit`"
    (let [maker (sut/make-logger {:logging
                                  {:type :inherit}})
          l (maker {} [])]
      
      (testing "creates logger that always returns `:inherit`"
        (is (= :inherit (sut/log-output l))))

      (testing "doesn't handle streams"
        (is (nil? (sut/handle-stream l :test))))))

  (testing "type `file`"
    (h/with-tmp-dir dir
      (let [maker (sut/make-logger {:logging
                                    {:type :file
                                     :dir dir}})]
        
        (testing "creates logger that returns file name"
          (let [l (maker {} ["test.txt"])
                f (sut/log-output l)]
            (is (file? f))
            (is (= (io/file dir "test.txt") f))))

        (testing "defaults to subdir from context work dir"
          (let [maker (sut/make-logger {:logging
                                        {:type :file}})
                l (maker {:work-dir dir} ["test.txt"])
                f (sut/log-output l)]
            (is (file? f))
            (is (= (io/file dir "logs" "test.txt") f))))

        (testing "creates parent dir"
          (let [l (maker {} ["logs" "test.txt"])
                f (sut/log-output l)]
            (is (.exists (.getParentFile f)))))

        (testing "doesn't handle streams"
          (is (nil? (-> (maker {} ["test.log"])
                        (sut/handle-stream :test))))))))

  (testing "type `oci`"
    (testing "creates logger that outputs to streams"
      (let [maker (sut/make-logger {:logging
                                    {:type :oci
                                     :bucket-name "test-bucket"}})
            l (maker {} ["test.log"])]
        (is (= :stream (sut/log-output l)))))

    (testing "handles stream by piping to bucket object"
      (with-redefs [oci/stream-to-bucket (fn [conf in]
                                           (md/future
                                             {:config conf
                                              :stream in}))]
        (let [maker (sut/make-logger {:logging
                                      {:type :oci
                                       :bucket-name "test-bucket"}})
              l (maker {:build {:sid ["test-cust" "test-proj" "test-repo"]}}
                       ["test.log"])
              r @(sut/handle-stream l :test-stream)]
          (is (= :test-stream (:stream r)))
          (is (= "test-cust/test-proj/test-repo/test.log"
                 (get-in r [:config :object-name]))))))

    (testing "adds prefix to object name"
      (with-redefs [oci/stream-to-bucket (fn [conf in]
                                           (md/future
                                             {:config conf
                                              :stream in}))]
        (let [maker (sut/make-logger {:logging
                                      {:type :oci
                                       :bucket-name "test-bucket"
                                       :prefix "logs"}})
              l (maker {:build {:sid ["test-cust" "test-proj" "test-repo"]}}
                       ["test.log"])
              r @(sut/handle-stream l :test-stream)]
          (is (= "logs/test-cust/test-proj/test-repo/test.log"
                 (get-in r [:config :object-name]))))))))
