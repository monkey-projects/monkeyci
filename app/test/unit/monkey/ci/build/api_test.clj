(ns monkey.ci.build.api-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [manifold.bus :as bus]
   [manifold.deferred :as md]
   [manifold.stream :as ms]
   [martian.core :as mc]
   [monkey.ci.build.api :as sut]
   [monkey.ci.build.api-server :as server]
   [monkey.ci.events.mailman :as em]
   [monkey.ci.events.mailman.build-api :as emba]
   [monkey.ci.protocols :as p]
   [monkey.ci.test.api-server :as tas]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.test.mailman :as tm]
   [monkey.mailman.core :as mmc])
  (:import
   (java.io PipedInputStream PipedOutputStream PrintWriter)))

(deftest api-client
  (let [config (tas/test-config)
        {:keys [token] :as s} (server/start-server config)
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))
        client (sut/make-client base-url token)]
    (with-open [srv (:server s)]
      
      (testing "can create api client"
        (is (fn? client)))

      (testing "can invoke test endpoint"
        (is (= {:result "ok"}
               (:body @(client (sut/as-edn {:path "/test"
                                            :method :get}))))))

      (testing "can post events"
        (let [broker (emba/make-broker client nil)
              event {:type ::test-event :message "test event"}
              get-posted #(tm/get-posted (:mailman config))]
          (is (some? (mmc/post-events broker [event])))
          (is (not= :timeout (h/wait-until #(not-empty (get-posted)) 1000)))
          (is (= event (-> (first (get-posted))
                           (select-keys (keys event))))))))))

(deftest build-params
  (testing "invokes `params` endpoint on client"
    (let [m (fn [req]
              (when (= "/params" (:path req))
                (md/success-deferred {:body
                                      [{:name "key"
                                        :value "value"}]})))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= {"key" "value"} (sut/build-params rt))))))

(deftest download-artifact
  (testing "invokes artifact download endpoint on client"
    (let [m (fn [req]
              (when (= "/artifact/test-artifact"
                       (:path req))
                (md/success-deferred {:body "test artifact contents"})))
          rt {:api {:client m}
              :build {:sid ["test-cust" "test-repo" "test-build"]}}]
      (is (= "test artifact contents"
             (sut/download-artifact rt "test-artifact"))))))

(deftest events-to-stream
  (let [input (PipedInputStream.)
        output (PipedOutputStream. input)
        client (fn [req]
                 (md/success-deferred {:status 200
                                       :body input}))
        [stream close] (sut/events-to-stream client)]
    (with-open [w (io/writer output)
                pw (PrintWriter. w)]
      (letfn [(post-event [evt]
                (.println pw (str "data: " (pr-str evt) "\n"))
                (.flush pw))]
        (testing "returns a manifold source"
          (is (ms/source? stream)))

        (testing "returns a close fn"
          (is (fn? close)))

        (testing "dispatches events"
          (let [evt {:type ::test-event :message "test event"}]
            (post-event evt)
            (is (= evt (-> stream (ms/take!) (deref 1000 :timeout))))))

        (testing "dispatches events to multiple listeners"
          (let [evt {:type ::test-event :message "other event"}]
            (post-event evt)
            (is (= evt (-> stream (ms/take!) (deref 1000 :timeout))))
            (is (nil? (ms/close! stream)))))))))

(deftest event-bus
  (let [input (PipedInputStream.)
        output (PipedOutputStream. input)
        client (fn [req]
                 (md/success-deferred {:status 200
                                       :body input}))
        bus (sut/event-bus client)]
    (with-open [w (io/writer output)
                pw (PrintWriter. w)]
      (letfn [(post-event [evt]
                (.println pw (str "data: " (pr-str evt) "\n"))
                (.flush pw))]
        (testing "returns an event bus"
          (is (some? (:bus bus))))

        (testing "returns a close fn"
          (is (fn? (:close bus))))

        (testing "dispatches events"
          (let [s (bus/subscribe (:bus bus) ::test-event)
                evt {:type ::test-event :message "test event"}]
            (is (ms/source? s))
            (post-event evt)
            (is (= evt (-> s (ms/take!) (deref 1000 :timeout))))
            (is (nil? (ms/close! s)))))

        (testing "dispatches events to multiple listeners"
          (let [s (bus/subscribe (:bus bus) ::test-event)
                evt {:type ::test-event :message "other event"}]
            (post-event evt)
            (is (= evt (-> s (ms/take!) (deref 1000 :timeout))))
            (is (nil? (ms/close! s)))))))))
