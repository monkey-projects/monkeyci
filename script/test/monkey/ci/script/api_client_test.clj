(ns monkey.ci.script.api-client-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [monkey.ci.script.api-client :as sut]
            [org.httpkit.server :as hs]))

(defn test-handler [req]
  (condp = (:uri req)
    "/test"
    {:status 200
     :body "Test response"}
    
    {:status 404
     :body (str "Not found: " (:uri req))}))

(defn- start-test-server []
  (hs/run-server test-handler {:port 0
                               :legacy-return-value? false}))

(defn with-test-server* [f]
  (let [server (start-test-server)]
    (try
      (f (hs/server-port server))
      (finally
        (hs/server-stop! server)))))

(defmacro with-test-server [p & body]
  `(with-test-server* (fn [~p]
                        ~@body)))

(deftest verify-server
  (with-test-server port
    (is (some? port))

    (let [cl (sut/make-client {:url (str "http://localhost:" port)})]
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

(deftest push-events
  (testing "pushes events using client"
    (let [pushed (atom [])
          events [{:type ::test-event}]
          m (fn [req]
              (when (and (= "/events" (:path req))
                         (= :post (:method req)))
                (reset! pushed (-> req :body edn/read-string))
                {:body (->stream (pr-str {}))
                 :status 202}))]
      (is (true? (sut/push-events m events)))
      (is (= events @pushed)))))

(deftest get-events
  (testing "returns SSE stream that receives events"
    (let [events [{:type ::first}
                  {:type ::second}]
          s (->> events
                 (map (comp (partial format "data: %s\n\n") pr-str))
                 (apply str))
          m (fn [req]
              (when (and (= "/events" (:path req))
                         (= :get (:method req)))
                {:body (->stream s)}))
          r (sut/get-events m)]
      (is (some? r))
      (is (= (first events) (first (ca/alts!! [r (ca/timeout 100)]))))
      (is (= (second events) (first (ca/alts!! [r (ca/timeout 100)]))))
      (is (nil? (first (ca/alts!! [r (ca/timeout 100)])))))))
