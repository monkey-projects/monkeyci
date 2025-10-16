(ns monkey.ci.build.api-server-test
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci.build.api-server :as sut]
            [monkey.ci.protocols :as p]
            [monkey.ci.spec.api-server :as aspec]
            [monkey.ci.test
             [aleph-test :as at]
             [api-server :as tas]
             [helpers :as h]
             [runtime :as trt]]
            [ring.mock.request :as mock]))

(def test-config (tas/test-config))

(deftest test-config-spec
  (testing "satisfies spec"
    (is (s/valid? ::aspec/config test-config)
        (s/explain-str ::aspec/config test-config))))

(deftest start-server
  (testing "can start tcp server"
    (let [s (sut/start-server test-config)]
      (is (map? s))
      (is (some? (:server s)))
      (is (string? (:token s)))
      (is (pos? (:port s)))
      (is (nil? (.close (:server s))))))

  ;; Not supported by netty 4
  #_(testing "can start uds server"
      (let [s (sut/start-server {:type :local})]
        (is (map? s))
        (is (some? (:server s)))
        (is (string? (:token s)))
        (is (string? (:socket s)))
        (is (nil? (.close (:server s)))))))

(deftest api-server
  (let [{:keys [token] :as s} (sut/start-server test-config)
        base-url (format "http://localhost:%d" (:port s))
        make-url (fn [path]
                   (str base-url "/" path))]
    (with-open [srv (:server s)]

      (testing "returns 401 if no token given"
        (is (= 401 (-> {:url (make-url "test")
                        :method :get
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))
      
      (testing "returns 401 if wrong token given"
        (is (= 401 (-> {:url (make-url "test")
                        :method :get
                        :headers {"Authorization" "Bearer wrong token"}
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status))))

      (testing "returns 200 if valid token given"
        (is (= 200 (-> {:url (make-url "test")
                        :method :get
                        :headers {"Authorization" (str "Bearer " token)}
                        :throw-exceptions false}
                       (http/request)
                       deref
                       :status)))))))

(defn- ->req
  "Creates a request object from given build context"
  [ctx]
  (h/->match-data {sut/context ctx}))

(defrecord FakeParams [params]
  p/BuildParams
  (get-build-params [_ _]
    (md/success-deferred params)))

(deftest get-params
  (let [repo (h/gen-repo)
        org (-> (h/gen-org)
                (assoc :repos {(:id repo) repo}))
        param-values [{:name "test-param"
                       :value "test value"}]
        build {:org-id (:id org)
               :repo-id (:id repo)}]
    
    (testing "fetches params using build params"
      (let [rec (->FakeParams param-values)
            req (->req {:params rec
                        :build build})]
        (is (= param-values
               (:body (sut/get-params req))))))))

(deftest get-params-from-api
  (let [repo (h/gen-repo)
        org (-> (h/gen-org)
                (assoc :repos {(:id repo) repo}))
        param-values [{:name "test-param"
                       :value "test value"}]
        build {:org-id (:id org)
               :repo-id (:id repo)}]
    
    (testing "retrieves from remote api"
      ;; Requests look different because of applied middleware
      (at/with-fake-http [{:url (format "http://test-api/org/%s/repo/%s/param"
                                        (:id org) (:id repo))
                           :method :get}
                          {:status 200
                           :body param-values
                           :headers {"Content-Type" "application/edn"}}]
        (is (= param-values
               (-> {:url "http://test-api"
                    :token "test-token"}
                   (sut/get-params-from-api build)
                   deref)))))))

(deftest decrypt-key-from-api
  (testing "invokes endpoint on general api"
    (let [org (h/gen-org)]
      (at/with-fake-http [{:url
                           (format "http://test-api/org/%s/crypto/decrypt-key" (:id org))
                           :method :post}
                          {:status 200
                           :body {:key "decrypted-key"}
                           :headers {"Content-Type" "application/edn"}}]
        (is (= "decrypted-key"
               @(sut/decrypt-key-from-api {:url "http://test-api"
                                           :token "test-token"}
                                          (:id org)
                                          "encrypted-key")))))))

(deftest download-workspace
  (testing "returns 204 no content if no workspace"
    (is (= 204 (-> {:workspace (h/fake-blob-store)}
                   (->req)
                   (sut/download-workspace)
                   :status))))

  (testing "returns workspace as stream"
    (let [ws-path "test/workspace"
          ws (h/fake-blob-store (atom {ws-path "Dummy contents"}))
          res (-> {:workspace ws}
                  (->req)
                  (sut/set-build {:workspace ws-path})
                  (sut/download-workspace))]
      (is (= 200 (:status res)))
      (is (not-empty (slurp (:body res)))))))

(deftest upload-artifact
  (let [bs (h/fake-blob-store)
        id (str (random-uuid))]
    (testing "stores artifact in blob store"
      (is (= 200 (-> {:artifacts bs}
                     (->req)
                     (assoc :parameters
                            {:path {:artifact-id id}}
                            :body (bs/to-input-stream (.getBytes "test body")))
                     (sut/upload-artifact)
                     :status))))

    (testing "client error if no body"
      (is (= 400 (-> {:artifacts bs}
                     (->req)
                     (assoc-in [:parameters :path :artifact-id] id)
                     (sut/upload-artifact)
                     :status))))))

(deftest download-artifact
  (let [build {:sid ["test-org" "test-repo" "test-build"]}]
    (testing "returns 404 not found if no artifact"
      (is (= 404 (-> {:artifacts (h/fake-blob-store)}
                     (->req)
                     (sut/set-build build)
                     (assoc-in [:parameters :path :artifact-id] "nonexisting")
                     (sut/download-artifact)
                     :status))))

    (testing "returns artifact as stream"
      (let [art-id "test-artifact"
            bs (h/fake-blob-store (atom {(str "test-org/test-repo/test-build/" art-id ".tgz") "Dummy contents"}))
            res (-> {:artifacts bs}
                    (->req)
                    (sut/set-build build)
                    (assoc-in [:parameters :path :artifact-id] art-id)
                    (sut/download-artifact))]
        (is (= 200 (:status res)))
        (is (not-empty (slurp (:body res))))))))

(deftest upload-cache
  (let [bs (h/fake-blob-store)
        id (str (random-uuid))]
    (testing "stores cache in blob store"
      (is (= 200 (-> {:cache bs}
                     (->req)
                     (assoc :parameters
                            {:path {:cache-id id}}
                            :body (bs/to-input-stream (.getBytes "test body")))
                     (sut/upload-cache)
                     :status))))

    (testing "client error if no body"
      (is (= 400 (-> {:cache bs}
                     (->req)
                     (assoc-in [:parameters :path :cache-id] id)
                     (sut/upload-cache)
                     :status))))))

(deftest download-cache
  (let [build {:sid ["test-org" "test-repo" "test-build"]}]
    (testing "returns 404 not found if no cache"
      (is (= 404 (-> {:cache (h/fake-blob-store)}
                     (->req)
                     (sut/set-build build)
                     (assoc-in [:parameters :path :cache-id] "nonexisting")
                     (sut/download-cache)
                     :status))))

    (testing "returns cache as stream"
      (let [cache-id "test-cache"
            bs (h/fake-blob-store (atom {(str "test-org/test-repo/" cache-id ".tgz") "Dummy contents"}))
            res (-> {:cache bs}
                    (->req)
                    (sut/set-build build)
                    (assoc-in [:parameters :path :cache-id] cache-id)
                    (sut/download-cache))]
        (is (= 200 (:status res)))
        (is (not-empty (slurp (:body res))))))))

(deftest get-ip-addr
  (testing "returns valid ip address"
    (is (some? (java.net.InetAddress/getByName (sut/get-ip-addr))))))

(deftest api-server-routes
  (let [token (sut/generate-token)
        config (-> test-config
                   (assoc :token token
                          :build {:org-id "test-org"
                                  :sid ["test-org" "test-repo" "test-build"]}))
        app (sut/make-app config)
        auth (fn [req]
               (mock/header req "Authorization" (str "Bearer " token)))]
    (testing "`/test` returns ok"
      (is (= 200 (-> (mock/request :get "/test")
                     (auth)
                     (app)
                     :status))))

    (testing "`GET /params` retrieves build params"
      (let [r (-> (mock/request :get "/params")
                  (auth)
                  (app))]
        (is (= 200 (:status r))
            (bs/to-string (:body r)))))

    (testing "`POST /events` dispatches events"
      (is (= 202 (-> (mock/request :post "/events")
                     (mock/body (pr-str [{:type ::test-event}]))
                     (mock/content-type "application/edn")
                     (auth)
                     (app)
                     :status))))

    (testing "`GET /events` receives events"
      (is (= 200 (-> (mock/request :get "/events")
                     (auth)
                     (app)
                     :status))))

    (testing "`GET /workspace` downloads workspace"
      (is (= 204 (-> (mock/request :get "/workspace")
                     (auth)
                     (app)
                     :status))))

    (testing "`/artifact`"
      (let [artifact-id (str (random-uuid))]
        (testing "`PUT` uploads artifact"
          (is (= 200 (-> (mock/request :put (str "/artifact/" artifact-id)
                                       {:body "test body"})
                         (auth)
                         (app)
                         :status)))
          (is (not-empty (-> config :artifacts :stored deref))))

        (testing "`GET` downloads artifact"
          (is (= 200 (-> (mock/request :get (str "/artifact/" artifact-id))
                         (auth)
                         (app)
                         :status))))))

    (testing "`/cache`"
      (let [cache-id (str (random-uuid))]
        (testing "`PUT` uploads cache"
          (is (= 200 (-> (mock/request :put (str "/cache/" cache-id)
                                       {:body "test body"})
                         (auth)
                         (app)
                         :status)))
          (is (not-empty (-> config :cache :stored deref))))

        (testing "`GET` downloads cache"
          (is (= 200 (-> (mock/request :get (str "/cache/" cache-id))
                         (auth)
                         (app)
                         :status))))))

    (testing "`/decrypt-key`"
      (testing "`POST` decrypts given key"
        (let [app (-> config
                      (assoc :key-decrypter
                             (fn [build _]
                               (md/success-deferred
                                (if (= "test-org" (:org-id build))
                                  "decrypted-key"
                                  (str "invalid build: " build)))))
                      (sut/make-app))
              r (-> (mock/request :post "/decrypt-key")
                    (mock/body (pr-str "encrypted-key"))
                    (mock/content-type "application/edn")
                    (auth)
                    (app))]
          (is (= 200 (:status r)))
          (is (= "decrypted-key"
                 (:body r))))))))
