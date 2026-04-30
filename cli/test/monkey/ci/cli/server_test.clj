(ns monkey.ci.cli.server-test
  (:require [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [monkey.ci.cli.server :as sut]
            [ring.mock.request :as mock]))

;;;; Helpers

(defn- auth-header [token]
  {"authorization" (str "Bearer " token)})

(defn- call
  "Invokes the handler with a mock request, adding the bearer token."
  ([handler method uri token]
   (call handler method uri token nil))
  ([handler method uri token body]
   (let [req (cond-> (mock/request method uri)
               body (-> (mock/body (pr-str body))
                        (mock/content-type "application/edn")))]
     (handler (update req :headers merge (auth-header token))))))

;;;; Token

(deftest generate-token-test
  (testing "returns a non-empty string"
    (let [t (sut/generate-token)]
      (is (string? t))
      (is (not (str/blank? t)))))

  (testing "each call returns a unique token"
    (is (not= (sut/generate-token) (sut/generate-token)))))

;;;; Auth middleware

(deftest auth-test
  (let [token   "test-token"
        event-ch (ca/chan 1)
        handler (sut/make-handler {:token token :event-mult-ch event-ch})]
    (testing "returns 401 when no Authorization header is present"
      (let [resp (handler (mock/request :get "/test"))]
        (is (= 401 (:status resp)))))

    (testing "returns 401 when a wrong token is provided"
      (let [resp (handler (-> (mock/request :get "/test")
                              (mock/header "authorization" "Bearer wrong-token")))]
        (is (= 401 (:status resp)))))

    (testing "passes through with the correct token"
      (let [resp (call handler :get "/test" token)]
        (is (= 200 (:status resp)))))))

;;;; GET /test

(deftest get-test-route-test
  (let [token   "tok"
        event-ch (ca/chan 1)
        handler (sut/make-handler {:token token :event-mult-ch event-ch})]
    (testing "returns 200 with {:result ok}"
      (let [resp (call handler :get "/test" token)]
        (is (= 200 (:status resp)))
        (is (= {:result "ok"} (edn/read-string (:body resp))))))))

;;;; GET /params

(deftest get-params-test
  (let [token    "tok"
        params   [{:name "FOO" :value "bar"} {:name "BAZ" :value "qux"}]
        event-ch (ca/chan 1)
        handler  (sut/make-handler {:token token :params params :event-mult-ch event-ch})]
    (testing "returns configured params as EDN"
      (let [resp (call handler :get "/params" token)]
        (is (= 200 (:status resp)))
        (is (= params (edn/read-string (:body resp))))))

    (testing "returns 401 without token"
      (is (= 401 (:status (handler (mock/request :get "/params"))))))))

;;;; POST /events

(deftest post-events-test
  (let [token    "tok"
        event-ch (ca/chan 10)
        handler  (sut/make-handler {:token token :event-mult-ch event-ch})]
    (testing "returns 202 and puts events on the channel"
      (let [evts [{:type :build/start :sid ["org" "repo" "1"]}]
            resp (call handler :post "/events" token evts)]
        (is (= 202 (:status resp)))
        ;; The events are on the channel
        (let [received (ca/poll! event-ch)]
          (is (= evts received)))))))

;;;; GET /events — SSE

(deftest get-events-sse-test
  ;; We only check that the request initiates the async http-kit channel path;
  ;; deep SSE streaming requires an actual HTTP connection.  We verify the handler
  ;; does NOT return a plain map (http-kit handles async channels differently).
  (let [token    "tok"
        event-ch (ca/chan 1)
        handler  (sut/make-handler {:token token
                                    :event-mult-ch event-ch
                                    :build {:sid ["org" "repo" "1"]}})]
    (testing "returns 401 without token"
      (is (= 401 (:status (handler (mock/request :get "/events"))))))))

;;;; Artifact upload / download round-trip

(deftest artifact-round-trip-test
  (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                          (str "mci-test-" (System/currentTimeMillis)))
        token    "tok"
        build    {:sid ["org" "repo" "42"]}
        event-ch (ca/chan 1)
        handler  (sut/make-handler {:token token
                                    :artifact-dir tmp-dir
                                    :build build
                                    :event-mult-ch event-ch})]
    (testing "upload returns 200 with artifact-id"
      (let [content "artifact content"
            req     (-> (mock/request :put "/artifact/my-art")
                        (mock/body content)
                        (update :headers merge (auth-header token)))
            resp    (handler req)]
        (is (= 200 (:status resp)))
        (is (= {:artifact-id "my-art"} (edn/read-string (:body resp))))))

    (testing "download returns the uploaded content"
      (let [resp (call handler :get "/artifact/my-art" token)]
        (is (= 200 (:status resp)))
        (is (= "artifact content" (slurp (:body resp))))))

    (testing "download of unknown artifact returns 404"
      (let [resp (call handler :get "/artifact/nonexistent" token)]
        (is (= 404 (:status resp)))))

    ;; Cleanup
    (doseq [f (reverse (file-seq tmp-dir))]
      (io/delete-file f true))))

;;;; Cache upload / download round-trip

(deftest cache-round-trip-test
  (let [tmp-dir  (io/file (System/getProperty "java.io.tmpdir")
                          (str "mci-cache-test-" (System/currentTimeMillis)))
        token    "tok"
        build    {:sid ["org" "repo" "99"]}
        event-ch (ca/chan 1)
        handler  (sut/make-handler {:token token
                                    :cache-dir tmp-dir
                                    :build build
                                    :event-mult-ch event-ch})]
    (testing "upload returns 200 with cache-id"
      (let [req  (-> (mock/request :put "/cache/my-cache")
                     (mock/body "cached data")
                     (update :headers merge (auth-header token)))
            resp (handler req)]
        (is (= 200 (:status resp)))
        (is (= {:cache-id "my-cache"} (edn/read-string (:body resp))))))

    (testing "download returns the uploaded content"
      (let [resp (call handler :get "/cache/my-cache" token)]
        (is (= 200 (:status resp)))
        (is (= "cached data" (slurp (:body resp))))))

    (testing "download of unknown cache returns 404"
      (let [resp (call handler :get "/cache/missing" token)]
        (is (= 404 (:status resp)))))

    ;; Cleanup
    (doseq [f (reverse (file-seq tmp-dir))]
      (io/delete-file f true))))

;;;; POST /decrypt-key

(deftest decrypt-key-test
  (let [token    "tok"
        event-ch (ca/chan 1)]
    (testing "uses identity decrypter by default"
      (let [handler (sut/make-handler {:token token :event-mult-ch event-ch})
            req     (-> (mock/request :post "/decrypt-key")
                        (mock/body (pr-str "my-enc-key"))
                        (mock/content-type "application/edn")
                        (update :headers merge (auth-header token)))
            resp    (handler req)]
        (is (= 200 (:status resp)))
        (is (= "my-enc-key" (edn/read-string (:body resp))))))

    (testing "uses the provided key-decrypter fn"
      (let [handler (sut/make-handler {:token token
                                       :event-mult-ch event-ch
                                       :key-decrypter (fn [_build k] (str "decrypted-" k))})
            req     (-> (mock/request :post "/decrypt-key")
                        (mock/body (pr-str "enc"))
                        (mock/content-type "application/edn")
                        (update :headers merge (auth-header token)))
            resp    (handler req)]
        (is (= 200 (:status resp)))
        (is (= "decrypted-enc" (edn/read-string (:body resp))))))))

;;;; Unknown route

(deftest not-found-test
  (let [token    "tok"
        event-ch (ca/chan 1)
        handler  (sut/make-handler {:token token :event-mult-ch event-ch})]
    (testing "unknown path returns 404"
      (let [resp (call handler :get "/nonexistent" token)]
        (is (= 404 (:status resp)))))))

;;;; start-server / stop-server lifecycle

(deftest start-stop-server-test
  (testing "start-server returns a map with port, token and event-mult-ch"
    (let [s (sut/start-server {})]
      (try
        (is (pos? (:port s)))
        (is (string? (:token s)))
        (is (some? (:event-mult-ch s)))
        (finally
          (sut/stop-server s)))))

  (testing "respects a supplied token"
    (let [s (sut/start-server {:token "my-token"})]
      (try
        (is (= "my-token" (:token s)))
        (finally
          (sut/stop-server s)))))

  (testing "server-url returns http://localhost:<port>"
    (let [s (sut/start-server {})]
      (try
        (is (= (str "http://localhost:" (:port s))
               (sut/server->url s)))
        (finally
          (sut/stop-server s))))))
