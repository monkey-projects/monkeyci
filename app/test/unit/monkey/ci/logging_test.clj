(ns monkey.ci.logging-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [manifold.deferred :as md]
   [monkey.ci.logging :as sut]
   [monkey.ci.oci :as oci]
   [monkey.ci.test.helpers :as h]
   [monkey.oci.os.core :as os]))

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
        
        (testing "creates logger that returns file name including sid without build id"
          (let [l (maker {:sid ["test-cust" "test-repo" "test-build"]}
                         ["test.txt"])
                f (sut/log-output l)]
            (is (file? f))
            (is (= (io/file dir "test-cust/test-repo/test.txt") f))))

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
              l (maker {:sid ["test-cust" "test-repo" "test-build"]}
                       ["test-build" "test.log"])
              r @(sut/handle-stream l :test-stream)]
          (is (= :test-stream (:stream r)))
          (is (= "test-cust/test-repo/test-build/test.log"
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
              l (maker {:sid ["test-cust" "test-repo" "test-build"]}
                       ["test-build" "test.log"])
              r @(sut/handle-stream l :test-stream)]
          (is (= "logs/test-cust/test-repo/test-build/test.log"
                 (get-in r [:config :object-name]))))))))

(deftest file-log-retriever
  (testing "lists log files in dir according to sid"
    (h/with-tmp-dir dir
      (let [l (sut/->FileLogRetriever dir)
            sid ["cust" "proj" "repo" (str (random-uuid))]
            build-dir (apply io/file dir sid)
            log-file (io/file build-dir "out.txt")
            contents "test log file contents"]
        (is (true? (.mkdirs build-dir)))
        (is (nil? (spit log-file contents)))
        (let [r (sut/list-logs l sid)]
          (is (= 1 (count r)))
          (is (= {:name "out.txt"
                  :size (count contents)}
                 (first r)))))))

  (testing "lists files recursively"
    (h/with-tmp-dir dir
      (let [l (sut/->FileLogRetriever dir)
            sid ["cust" "proj" "repo" (str (random-uuid))]
            log-dir (apply io/file dir (conj sid "sub"))
            log-file (io/file log-dir "out.txt")]
        (is (true? (.mkdirs log-dir)))
        (is (nil? (spit log-file "test log file contents")))
        (let [r (sut/list-logs l sid)]
          (is (= 1 (count r)))
          (is (= "sub/out.txt" (:name (first r))))))))

  (testing "fetches log from file according to sid and path"
    (h/with-tmp-dir dir
      (let [l (sut/->FileLogRetriever dir)
            sid ["test" "sid"]
            log-file "sub/out.txt"
            contents "test log file contents"]
        (is (true? (.mkdirs (apply io/file dir (conj sid "sub")))))
        (is (nil? (spit (io/file (apply io/file dir sid) log-file) contents)))
        (is (= contents (slurp (sut/fetch-log l sid log-file))))))))

(deftest make-log-retriever
  (testing "can create for type `file`"
    (is (some? (sut/make-log-retriever {:logging {:type :file}}))))

  (testing "can create for type `oci`"
    (is (some? (sut/make-log-retriever {:logging {:type :oci}})))))

(deftest oci-bucket-log-retriever
  (testing "`list-logs`"
    (testing "lists all files with build sid prefix"
      (let [logger (sut/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"}})]
        (with-redefs [os/list-objects (constantly (md/success-deferred
                                                   {:objects [{:name "a/b/c/out.txt"
                                                               :size 100}]}))]
          (is (= [{:name "out.txt" :size 100}]
                 (sut/list-logs logger ["a" "b" "c"]))))))

    (testing "prepends configured prefix"
      (let [logger (sut/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"
                                                      :prefix "logs"}})]
        (with-redefs [os/list-objects (fn [_ opts]
                                        (if (= "logs/a/b/c/" (:prefix opts))
                                          (md/success-deferred
                                           {:objects [{:name "logs/a/b/c/out.txt"
                                                       :size 100}]})
                                          (md/error-deferred (ex-info "invalid prefix" opts))))]
          (is (= [{:name "out.txt" :size 100}]
                 (sut/list-logs logger ["a" "b" "c"])))))))

  (testing "`fetch-log`"
    (testing "fetches log by downloading object"
      (let [logger (sut/make-log-retriever {:logging {:type :oci
                                                      :bucket-name "test-bucket"}})
            contents "this is a test object"]
        (with-redefs [os/get-object (constantly (md/success-deferred
                                                 contents))]
          (is (= contents
                 (slurp (sut/fetch-log logger ["a" "b" "c"] "out.txt")))))))

    (testing "prepends object name with configured prefix"
      (let [logger (sut/make-log-retriever {:logging {:type :oci
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
                 (slurp (sut/fetch-log logger ["a" "b" "c"] "out.txt")))))))))

