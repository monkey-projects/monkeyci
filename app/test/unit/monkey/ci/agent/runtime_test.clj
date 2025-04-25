(ns monkey.ci.agent.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.agent.runtime :as sut]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.protocols :as p]
            [monkey.ci.test
             [aleph-test :as ta]
             [config :as tc]
             [helpers :as h]]))

(deftest make-system
  (let [conf tc/base-config
        sys (sut/make-system conf)]
    (testing "provides build atom"
      (is (some? (:builds sys))))

    (testing "provides mailman event handler"
      (is (some? (:mailman sys))))

    (testing "provides global mailman event handler"
      (is (some? (:global-mailman sys))))

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

    (testing "provides metris"
      (is (some? (:metrics sys))))))

(deftest params
  (let [params (sut/new-params {:api {:url "http://test-api"}
                                :jwk {:priv (h/generate-private-key)}})
        build {:build-id "test-build"}
        requests (atom [])]
    (with-redefs [bas/get-params-from-api (fn [conf build]
                                            (swap! requests conj {:conf conf
                                                                  :build build})
                                            {})]
      (testing "generates new token per request"
        (is (some? (p/get-build-params params build)))
        (let [req (-> @requests last :conf)]
          (is (some? (:token req)))
          (is (= "http://test-api" (:url req))))))))

(deftest ssh-keys-fetcher
  (testing "retrieves from global api for repo"
    (ta/with-fake-http
        ["http://test-api/customer/test-cust/repo/test-repo/ssh-keys"
         (fn [req]
           {:status 200
            :body (pr-str ["test-key"])})]
      (let [sid ["test-cust" "test-repo" "test-build"]
            fetcher (sut/new-ssh-keys-fetcher {:api {:url "http://test-api"}
                                               :jwk {:priv (h/generate-private-key)}})]
        (is (= ["test-key"] (fetcher sid)))))))
