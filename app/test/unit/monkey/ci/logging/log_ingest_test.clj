(ns monkey.ci.logging.log-ingest-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [logging :as l]]
            [monkey.ci.logging.log-ingest :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest make-client
  (testing "creates client object according to params"
    (is (fn? (sut/make-client {:url "http://test"}))))

  (testing "invokes http request"
    (let [req (atom [])
          c (sut/make-client {:url "http://test"})]
      (with-redefs [http/request (partial swap! req conj)]
        (testing "for push"
          (is (some? (c :push ["test" "path"] ["test-logs"])))
          (is (= 1 (count @req)))
          (let [r (last @req)]
            (is (= :post (:method r)))
            (is (= "http://test/log/test/path" (:url r)))
            (is (= (pr-str {:entries ["test-logs"]})
                   (:body r)))))

        (testing "for fetch"
          (is (some? (c :fetch ["test" "path"])))
          (let [r (last @req)]
            (is (= :get (:method r)))
            (is (= "http://test/log/test/path" (:url r)))))))))

(deftest push-logs
  (testing "pushes to client"
    (let [inv (atom {})
          c (fn [req & args]
              (swap! inv assoc req args))]
      (is (some? (sut/push-logs c ["test" "path"] ["test-logs"])))
      (is (= [["test" "path"] ["test-logs"]]
             (get @inv :push))))))

(deftest push-logs
  (testing "fetches from remote using client"
    (let [inv (atom {})
          c (fn [req & args]
              (swap! inv assoc req args))]
      (is (some? (sut/fetch-logs c ["test" "path"])))
      (is (= [["test" "path"]]
             (get @inv :fetch))))))

(deftest pushing-stream
  (testing "returns output stream"
    (let [os (sut/pushing-stream (constantly nil)
                                 {:interval 1000})]
      (is (instance? java.io.OutputStream os))
      (is (nil? (.close os)))))

  (testing "pushes written data to client on full buffer"
    (let [inv (atom {})
          c (fn [req & args]
              (md/success-deferred (swap! inv assoc req args)))
          ps (sut/pushing-stream c
                                 {:path ["test" "path"]
                                  :interval 1000
                                  :buf-size 10})]
      (is (nil? (.write ps (.getBytes "0123456789"))))
      (is (= [["test" "path"] ["0123456789"]]
             (h/wait-until #(get @inv :push) 1000)))
      (is (nil? (.close ps))))))

(deftest log-ingest-logger
  (let [build (h/gen-build)
        path ["test" "path"]
        inv (atom nil)
        client (fn [_ path logs]
                 (md/success-deferred (reset! inv [path logs])))
        logger (sut/make-ingest-logger client {:interval 100}
                                       build "test/path")
        s (l/log-output logger)]
    
    (testing "creates output stream"
      (is (instance? java.io.OutputStream s)))

    (testing "pushes to build sid + path"
      (is (nil? (.write s (.getBytes "this is a test"))))
      (is (not= :timeout (h/wait-until #(some? @inv) 200)))
      (is (= (concat (b/sid build) path)
             (first @inv))))

    (is (nil? (.close s)))))

(deftest log-ingest-retriever
  (testing "retrieves log contents using client"
    (let [client (constantly (md/success-deferred {:entries ["test-log"]}))
          r (sut/make-log-ingest-retriever client)
          f (l/fetch-log r ["test" "build"] "test-path")]
      (is (instance? java.io.InputStream f))
      (is (= "test-log" (slurp f))))))
