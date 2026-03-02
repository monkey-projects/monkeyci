(ns monkey.ci.logging.log-ingest-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [babashka.fs :as fs]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [build :as b]
             [edn :as edn]]
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
          (is (some? (c :push ["test" "path"] [{:ts 100
                                                :contents "test-logs"}])))
          (is (= 1 (count @req)))
          (let [r (last @req)]
            (is (= :post (:method r)))
            (is (= "http://test/log/test/path" (:url r)))
            (is (= "test-logs"
                   (-> (:body r)
                       (edn/edn->)
                       :entries
                       first
                       :contents)))))

        (testing "for fetch"
          (is (some? (c :fetch ["test" "path"])))
          (let [r (last @req)]
            (is (= :get (:method r)))
            (is (= "http://test/log/test/path" (:url r))))))))

  (testing "passes configured headers"
    (with-redefs [http/request (fn [req]
                                 (if (nil? (get-in req [:headers "test-header"]))
                                   (md/error-deferred (ex-info "invalid headers" req))
                                   (md/success-deferred {:status 200})))]
      (let [c (sut/make-client {:url "http://test" :headers {"test-header" "test-value"}})]
        (is (= 200 (-> (c :fetch ["test" "path"])
                       (deref)
                       :status)))))))

(deftest push-logs
  (testing "pushes to client"
    (let [inv (atom {})
          c (fn [req & args]
              (swap! inv assoc req args)
              (md/success-deferred {:status 204}))]
      (is (true? @(sut/push-logs c ["test" "path"] ["test-logs"])))
      (is (= [["test" "path"] ["test-logs"]]
             (get @inv :push))))))

(deftest fetch-logs
  (testing "fetches from remote using client"
    (let [inv (atom {})
          result {:entries [{:ts "1"
                             :contents "test contents"}]}
          c (fn [req & args]
              (swap! inv assoc req args)
              (md/success-deferred
               {:status 200
                :body (pr-str result)}))]
      (is (= result @(sut/fetch-logs c ["test" "path"])))
      (is (= [["test" "path"]]
             (get @inv :fetch)))))

  (testing "`nil` if empty body"
    (is (nil? @(sut/fetch-logs (constantly
                                (md/success-deferred
                                 {:status 200
                                  :body ""}))
                               ["test" "path"])))))

(deftest make-sink
  (testing "pushes written data to client on full buffer"
    (let [inv (atom {})
          c (fn [req & args]
              (md/success-deferred (swap! inv assoc req args)))
          s (sut/make-sink c
                           ["test" "path"]
                           {:interval 1000
                            :buf-size 10})
          buf (.getBytes "0123456789")]
      (is (true? (deref (ms/put! s ::sut/eof) 100 :timeout))
          "ignores eof")
      (is (true? (deref (ms/put! s {:buf buf :len (alength buf) :off 0}) 100 :timeout)))
      (let [r (h/wait-until #(get @inv :push) 1000)]
        (is (= ["test" "path"] (first r)))
        (is (= "0123456789" (-> r second first :contents)))
        (is (number? (-> r second first :ts))))
               
      (is (nil? (ms/close! s))))))

(deftest pushing-stream
  (testing "returns output stream"
    (let [os (sut/pushing-stream (constantly nil)
                                 {:interval 1000})]
      (is (instance? java.io.OutputStream os))
      (is (nil? (.close os))))))

(deftest stream-file
  (h/with-tmp-dir dir
    (let [out (ms/stream 1)
          in (ms/filter (partial not= ::sut/eof) out)
          f (fs/path dir "test.log")
          msg "this is a test"
          _ (spit (fs/file f) msg)
          r (sut/stream-file f out {:interval 100})]
      (testing "returns deferred"
        (is (md/deferred? r)))

      (testing "pushes the log contents to stream"
        (let [r @(ms/try-take! in nil 200 :timeout)]
          (is (not= :timeout r))
          (is (= msg (String. (:buf r) (:off r) (:len r))))))

      (testing "continues reading until sink is closed"
        (let [new-msg "this is another message"]
          (is (nil? (spit (fs/file f) new-msg :append true)))
          (let [r @(ms/try-take! in nil 200 :timeout)]
            (is (map? r))
            (is (= new-msg (String. (:buf r) (:off r) (:len r)))))))

      (testing "stops when sink closed"
        (is (nil? (ms/close! out)))
        (is (= ::sut/sink-closed (deref r 500 :timeout)))))))
