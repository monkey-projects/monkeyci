(ns monkey.ci.script.api-client-test
  (:require [babashka.fs :as fs]
            [buddy.core.codecs :as bcc]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]]
            [monkey.ci.blob.disk :as bd]
            [monkey.ci.build
             [api-server :as server]]
            [monkey.ci.script
             [api-client :as sut]
             [mailman :as emba]]
            [monkey.ci.test
             [api-server :as tas]
             [helpers :as h]
             [mailman :as tm]]
            [monkey.ci.vault.common :as vc]
            [monkey.ci.web.crypto :as crypto]
            [monkey.mailman.core :as mmc])
  (:import (java.io PipedInputStream PipedOutputStream PrintWriter)))

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

      (testing "fails on invalid token"
        (let [c (sut/make-client base-url "invalid-token")]
          (is (thrown? Exception
                       @(c {:path "/test" :method :get})))))

      (testing "fails on http error"
        (is (thrown? Exception
                     @(client {:path "/not-found" :method :get}))))

      (testing "can post events"
        (let [broker (emba/make-broker client nil)
              event {:type ::test-event :message "test event"}
              get-posted #(tm/get-posted (:mailman config))]
          (is (some? (mmc/post-events broker [event])))
          (is (not= :timeout (h/wait-until #(not-empty (get-posted)) 1000)))
          (is (= event (-> (first (get-posted))
                           (select-keys (keys event)))))))

      (testing "can decrypt key"
        (is (= "decrypted" (sut/decrypt-key client "encrypted")))))))

(defn- ->stream [str]
  (java.io.ByteArrayInputStream.
   (.getBytes str)))

(deftest decrypt-key
  (testing "invokes key decryption endpoint on client"
    (let [m (fn [req]
              (when (and (= "/decrypt-key" (:path req))
                         (= :post (:request-method req)))
                (md/success-deferred {:body (->stream "decrypted key")})))]
      (is (= "decrypted key" (sut/decrypt-key m "encrypted-key"))))))

(deftest build-params
  (let [dek (vc/generate-key)
        m (fn [req]
            (cond
              (= "/params" (:path req))
              (md/success-deferred {:body
                                    [{:name "key"
                                      :value "value"}]})
              (= "/decrypt-key" (:path req))
              (md/success-deferred {:body (->stream (bcc/bytes->b64-str dek))})))
        org-id (cuid/random-cuid)
        rt {:api {:client m}
            :build
            {:org-id org-id
             :sid [org-id "test-repo" "test-build"]
             :dek "encrypted-dek"
             :params {"extra-param" (crypto/encrypt dek (crypto/cuid->iv org-id) "extra-val")}}}
        params (sut/build-params rt)]
    
    (testing "invokes `params` endpoint on client"
      (is (= "value" (get params "key"))))

    (testing "adds decrypted additional build params"
      (is (= "extra-val" (get params "extra-param"))))))

(deftest download-artifact
  (testing "invokes artifact download endpoint on client"
    (let [m (fn [req]
              (when (= "/artifact/test-artifact"
                       (:path req))
                (md/success-deferred {:body "test artifact contents"})))
          rt {:api {:client m}
              :build {:sid ["test-org" "test-repo" "test-build"]}}]
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

(deftest build-api-artifact-repository
  (h/with-tmp-dir dir
    (let [store-dir (fs/path dir "store")
          _ (fs/create-dir store-dir)
          store (bd/->DiskBlobStore (str store-dir))
          sid (repeatedly 3 (comp str random-uuid))
          build {:sid sid}
          server (-> (tas/test-config)
                     (assoc :artifacts store
                            :build build)
                     (server/start-server))
          client (sut/make-client (format "http://localhost:%d" (:port server)) (:token server))
          art-id (str (random-uuid))
          in-dir (fs/path dir "input")
          out-dir (fs/path dir "output")
          _ (fs/create-dir in-dir)
          _ (spit (fs/file (fs/path in-dir "test.txt")) "This is a test file")
          repo (sut/make-artifact-repository client)]
      (with-open [s (:server server)]
        (testing "uploads artifact using api"
          (is (= art-id (:artifact-id @(p/save-artifact repo sid art-id (str in-dir))))))
        
        (testing "downloads artifact using api"
          (is (some? @(p/restore-artifact repo sid art-id (str out-dir))))
          (is (fs/exists? (fs/path out-dir "test.txt"))))

        (testing "does nothing if artifact does not exist"
          (is (nil? @(p/restore-artifact repo sid "nonexisting" (str out-dir)))))))))

