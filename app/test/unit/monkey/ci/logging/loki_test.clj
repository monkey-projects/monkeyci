(ns monkey.ci.logging.loki-test
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [monkey.ci.helpers :as h]
            [monkey.ci.logging.loki :as sut]
            [monkey.ci.logging :as l])
  (:import [java.io PipedReader PipedWriter PrintWriter]))

(deftest post-to-loki
  (testing "sends request"
    (with-redefs [http/request (fn [req]
                                 (md/success-deferred {:status 200
                                                       :body {:request req}}))]
      (let [r (sut/post-to-loki {:url "http://loki"
                                 :tenant-id "test-tenant"
                                 :token "test-token"}
                                [{:stream
                                  {:label "value"}
                                  :values
                                  [[100 "Line 1"]
                                   [200 "Line 2"]]}])]
        (is (some? r))
        (is (= 200 (:status @r)))

        (testing "to configured loki endpoint"
          (is (= "http://loki" (get-in @r [:body :request :url]))))

        (testing "converts millis to nanos"
          (let [rb (-> (get-in @r [:body :request :body])
                       (h/parse-json))]
            (is (= "100000000" (-> rb
                                   :streams
                                   first
                                   :values
                                   ffirst))
                "converts millis to nanos as string")))

        (testing "sets tenant id in header"
          (is (= "test-tenant" (get-in @r [:body :request :headers "X-Scope-OrgID"]))))

        (testing "sets bearer token in header"
          (is (= "Bearer test-token" (get-in @r [:body :request :headers "Authorization"]))))))))

(deftest stream-to-loki
  (let [inv (atom [])
        reader (PipedReader.)
        writer (PipedWriter. reader)
        pw (PrintWriter. writer)]
    (with-redefs [sut/post-to-loki (fn [_ streams]
                                     (swap! inv conj streams))]
      (let [r (sut/stream-to-loki reader {})]
        (is (md/deferred? r))
        (is (some? (md/timeout! r 1000))) ; Safety
        (is (nil? (.close reader)))
        (is (nil? (.close writer)))
        
        (testing "resolves on reader close"
          (is (map? @r)))

        (testing "returns number of posted lines"
          (is (= 0 (:line-count @r))))))))

(deftest post-lines
  (with-redefs [sut/post-to-loki (fn [conf streams]
                                (md/success-deferred
                                 {:conf conf
                                  :streams streams}))]
    (testing "posts lines to loki"
      (let [conf {:url "http://loki"}
            lines [[100 "line 1"]
                   [200 "line 2"]]
            r (sut/post-lines lines conf)]
        (is (= conf (:conf r)))
        (is (= [{:values lines}]
               (:streams r)))))

    (testing "passes labels from config"
      (let [labels {:key "value"}]
        (is (= labels (-> (sut/post-lines [[100 "test line"]] {:labels labels})
                          :streams
                          first
                          :stream)))))

    (testing "does nothing if no data"
      (is (nil? (-> (sut/post-lines [] {})))))))

(defn with-fake-loki* [posts f]
  (with-redefs [sut/post-to-loki (fn [_ streams]
                                   (swap! posts conj streams)
                                   (md/success-deferred {:status 200}))]
    (f)))

(defmacro with-fake-loki [posts & body]
  `(let [~posts (atom [])]
     (with-fake-loki* ~posts (fn [] ~@body))))

(deftest post-or-acc
  (testing "posts if max lines reached"
    (with-fake-loki posts
      (is (empty? (sut/post-or-acc [200 "New line"]
                                   [[100 "Previous line"]]
                                   {:threshold {:lines 2}})))
      (is (= 1 (count @posts)))
      (is (= ["Previous line" "New line"]
             (map second (:values (ffirst @posts)))))))

  (testing "posts if timeout reached"
    (with-fake-loki posts
      (is (empty? (sut/post-or-acc [200 "New line"]
                                   [[100 "Previous line"]]
                                   {:threshold {:timeout 1000}
                                    :now 2000})))
      (is (= 1 (count @posts)))
      (is (= ["Previous line" "New line"]
             (map second (:values (ffirst @posts))))))))
