(ns monkey.ci.agent.runtime-test
  (:require [buddy.core.keys :as bck]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.agent.runtime :as sut]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci
             [protocols :as p]
             [sid :as sid]]
            [monkey.ci.web.auth :as auth]
            [monkey.ci.test
             [aleph-test :as ta]
             [config :as tc]
             [helpers :as h]]))

(deftest make-system
  (let [conf (assoc tc/base-config
                    :poll-loop {:type :manifold})
        sys (sut/make-system conf)]
    (testing "provides build atom"
      (is (some? (:builds sys))))

    (testing "provides mailman event handler"
      (is (some? (:mailman sys))))

    (testing "provides event forwarder"
      (is (some? (:event-forwarder sys))))

    (testing "provides artifacts"
      (is (some? (:artifacts sys))))

    (testing "provides cache"
      (is (some? (:cache sys))))

    (testing "provides workspace"
      (is (some? (:workspace sys))))

    (testing "provides api server"
      (is (some? (:api-server sys))))

    (testing "provides git clone fn"
      (is (fn? (-> sys :git :clone))))

    (testing "provides ssh keys fetcher fn"
      (is (fn? (:ssh-keys-fetcher sys))))

    (testing "provides agent routes"
      (is (some? (:agent-routes sys))))

    (testing "provides build params"
      (is (some? (:params sys))))

    (testing "provides container routes"
      (is (some? (:container-routes sys))))

    (testing "provides metrics"
      (is (some? (:metrics sys))))

    (testing "provides poll loop"
      (is (some? (:poll-loop sys))))

    (testing "provides key decrypter"
      (is (fn? (:key-decrypter sys))))))

(deftest params
  (let [kp (auth/generate-keypair)
        params (sut/new-params {:api {:url "http://test-api"}
                                :jwk {:priv (.getPrivate kp)}})
        build {:org-id "test-org"
               :repo-id "test-repo"
               :build-id "test-build"}
        requests (atom [])]
    (with-redefs [bas/get-params-from-api (fn [conf build]
                                            (swap! requests conj {:conf conf
                                                                  :build build})
                                            {})]
      (testing "generates new token per request"
        (is (some? (p/get-build-params params build)))
        (let [req (-> @requests last :conf)
              token (:token req)]
          (is (some? token))
          (is (= "test-org/test-repo/test-build" (-> token
                                                     (jwt/unsign (.getPublic kp) {:alg :rs256})
                                                     :sub)))
          (is (= "http://test-api" (:url req))))))))

(deftest ssh-keys-fetcher
  (let [sid ["test-cust" "test-repo" "test-build"]
        kp (auth/generate-keypair)
        conf {:api {:url "http://test-api"}
              :jwk {:priv (.getPrivate kp)}}
        pubkey (.getPublic kp)]
    (testing "retrieves from global api for repo"
      (ta/with-fake-http
          ["http://test-api/org/test-cust/repo/test-repo/ssh-keys"
           (fn [req]
             {:status 200
              :body (pr-str ["test-key"])})]
        (let [fetcher (sut/new-ssh-keys-fetcher conf)]
          (is (= ["test-key"] (fetcher sid))))))

    (testing "passes valid token for build"
      (ta/with-fake-http
          ["http://test-api/org/test-cust/repo/test-repo/ssh-keys"
           (fn [req]
             (let [token (get-in req [:headers "authorization"])]
               {:status 200
                :body (pr-str [token])}))]
        (let [fetcher (sut/new-ssh-keys-fetcher conf)
              token (->> (fetcher sid)
                         (first)
                         (re-matches #"^Bearer (.*)$")
                         (second))]
          (is (string? token))
          (is (= sid (-> (jwt/unsign token pubkey {:alg :rs256})
                         :sub
                         (sid/parse-sid)))))))

    (testing "throws on error response"
      (ta/with-fake-http
          ["http://test-api/org/test-cust/repo/test-repo/ssh-keys"
           (fn [req]
             {:status 400
              :body (java.io.ByteArrayInputStream. (.getBytes "Test client error"))})]
        (let [fetcher (sut/new-ssh-keys-fetcher conf)]
          (is (thrown-with-msg? Exception #"Test client error" (fetcher sid))))))))

(deftest build-key-decrypter
  (testing "decrypts from global api"
    (ta/with-fake-http
        ["http://test-api/org/test-org/crypto/decrypt-key"
         (fn [req]
           {:status 200
            :body {:key "decrypted-key"}})]
      (let [build (-> (h/gen-build)
                      (assoc :org-id "test-org"))
            d (sut/new-build-key-decrypter
               {:api {:url "http://test-api"}
                :jwk {:priv (h/generate-private-key)}})]
        (is (= "decrypted-key" @(d build "enc-key")))))))

(deftest container-key-decrypter
  (testing "invokes global api endpoint"
    (ta/with-fake-http
        ["http://test-api/org/test-org/crypto/decrypt-key"
         (fn [req]
           {:status 200
            :body {:key "decrypted-key"}})]
      (let [org-id "test-org"
            d (sut/new-container-key-decrypter
               {:api {:url "http://test-api"}
                :jwk {:priv (h/generate-private-key)}})]
        (is (= "decrypted-key" @(d "enc-key" [org-id])))))))

(deftest poll-loop
  (let [conf {:poll-loop
              {:max-builds 0
               :poll-interval 100}}
        p (-> (sut/new-poll-loop conf)
              (assoc :builds (atom {}))
              (co/start))]
    
    (testing "`start`"
      (testing "creates polling future"
        (is (some? (:future p)))
        (is (true? @(:running? p)))))

    (testing "`stop` stops poll loop"
      (let [r (co/stop p)]
        (is (nil? (:future r)))
        (is (false? @(:running? p)))))))

(deftest container-system
  (let [sys (sut/make-container-system (-> tc/base-config
                                           (assoc :poll-loop {:type :manifold})))]
    (testing "provides event broker"
      (is (some? (:mailman sys))))
    
    (testing "provides container routes"
      (is (some? (:container-routes sys))))

    (testing "provides poll loop"
      (is (some? (:poll-loop sys))))

    (testing "provides state"
      (is (some? (:state sys))))

    (testing "provides key decrypter"
      (is (fn? (:key-decrypter sys))))))
