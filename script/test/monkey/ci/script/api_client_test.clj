(ns monkey.ci.script.api-client-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [monkey.ci.script.api-client :as sut]
            [org.httpkit
             [client :as hc]
             [server :as hs]])
  (:import [java.net UnixDomainSocketAddress StandardProtocolFamily]
           [java.nio.channels SocketChannel ServerSocketChannel]))

(defn test-handler [req]
  (condp = (:uri req)
    "/test"
    {:status 200
     :body "Test response"}
    
    {:status 404
     :body (str "Not found: " (:uri req))}))

(defn start-test-server
  "Creates a test server that uses a UDS socket for connections"
  []
  (let [dir (fs/create-temp-dir {:prefix "monkeyci-"})
        sock (fs/path dir "test.sock")]
    {:socket sock
     :server (hs/run-server test-handler
                            {:address-finder #(UnixDomainSocketAddress/of sock)
                             :channel-factory (fn [_addr]
                                                (ServerSocketChannel/open StandardProtocolFamily/UNIX))
                             :legacy-return-value? false})}))

(defn test-client [sock]
  (hc/make-client {:address-finder (fn [_uri]
                                     (UnixDomainSocketAddress/of sock))
                   :channel-factory (fn [_addr]
                                      (SocketChannel/open StandardProtocolFamily/UNIX))}))

(defn with-test-server* [f]
  (let [{:keys [socket server]} (start-test-server)]
    (try
      (f socket)
      (finally
        (hs/server-stop! server)))))

(defmacro with-test-server [s & body]
  `(with-test-server* (fn [~s]
                        ~@body)))

(deftest verify-server
  (with-test-server s
    (is (some? s))

    (let [cl (sut/make-client {:client (test-client s)
                               :url "http://local-test"})]
      (testing "handles test request"
        (is (= 200 (:status (cl {:method :get :path "/test"})))))

      (testing "throws on error"
        (is (thrown? Exception (cl {:method :get :path "/nonexisting"})))))))

(defn- ->stream [str]
  (java.io.ByteArrayInputStream.
   (.getBytes str)))

(deftest decrypt-key*
  (testing "invokes key decryption endpoint on client"
    (let [m (fn [req]
              (when (and (= "/decrypt-key" (:path req))
                         (= :post (:method req)))
                {:body (->stream "decrypted key")}))]
      (is (= "decrypted key" (sut/decrypt-key* m "encrypted-key"))))))

(deftest build-params
  (testing "invokes `params` endpoint on client"
    (let [m (fn [req]
              (when (and (= "/params" (:path req))
                         (= :get (:method req)))
                {:body (->stream (pr-str [{:name "test-param"
                                           :value "test-val"}]))}))]
      (is (= {"test-param" "test-val"} (sut/build-params {:api {:client m}}))))))

(deftest get-artifact
  (testing "invokes endpoint using client"
    (let [m (fn [req]
              (when (and (= "/artifact/test-artifact" (:path req))
                         (= :get (:method req)))
                {:body (->stream (pr-str {:path (get-in req [:query-params :path])}))}))]
      (is (= "/test/path" (sut/get-artifact m "test-artifact" "/test/path"))))))

(deftest put-artifact
  (testing "invokes endpoint using client"
    (let [m (fn [req]
              (let [p (-> req :body edn/read-string :path)]
                (when (and (= "/artifact/test-artifact" (:path req))
                           (= :post (:method req))
                           (= "/test/path" p))
                  {:body (->stream (pr-str {:path p}))})))]
      (is (= "/test/path" (sut/put-artifact m "test-artifact" "/test/path"))))))

(deftest get-cache
  (testing "invokes endpoint using client"
    (let [m (fn [req]
              (when (and (= "/cache/test-cache" (:path req))
                         (= :get (:method req)))
                {:body (->stream (pr-str {:path (get-in req [:query-params :path])}))}))]
      (is (= "/test/path" (sut/get-cache m "test-cache" "/test/path"))))))

(deftest put-cache
  (testing "invokes endpoint using client"
    (let [m (fn [req]
              (let [p (-> req :body edn/read-string :path)]
                (when (and (= "/cache/test-cache" (:path req))
                           (= :post (:method req))
                           (= "/test/path" p))
                  {:body (->stream (pr-str {:path p}))})))]
      (is (= "/test/path" (sut/put-cache m "test-cache" "/test/path"))))))
