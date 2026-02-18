(ns monkey.ci.logging.log-ingest-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [babashka.fs :as fs]
            [manifold
             [deferred :as md]
             [stream :as ms]]
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

(deftest stream-file
  (h/with-tmp-dir dir
    (let [;; pushed (atom {})
          ;; client (fn [_ path s]
          ;;          (swap! pushed (fn [r]
          ;;                          (-> r
          ;;                              (assoc :path path)
          ;;                              (update :contents conj s))))
          ;;          (md/success-deferred {:status 200}))
          ;; path ["test" "path"]
          s (ms/stream 1)
          f (fs/path dir "test.log")
          msg "this is a test"
          _ (spit (fs/file f) msg)
          r (sut/stream-file f s {:interval 100})]
      (testing "returns deferred"
        (is (md/deferred? r)))

      (testing "pushes the log contents to stream"
        (let [r (deref (ms/take! s) 200 :timeout)]
          (is (not= :timeout r))
          (is (= msg (String. (:buf r) (:off r) (:len r))))))

      (testing "returns `::eof` on successful read until eof"
        (is (= ::sut/eof (deref r 200 :timeout)))))))

(deftest wait-for-file
  (h/with-tmp-dir dir
    (let [p (fs/path dir "test.txt")
          w (sut/wait-for-file p)]
      (testing "returns a deferred"
        (is (md/deferred? w)))

      (testing "realizes when file exists"
        (is (not (md/realized? w)))
        (is (nil? (spit (fs/file p) "test")))
        (is (= p (deref w 100 :timeout)))))))
