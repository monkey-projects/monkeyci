(ns monkey.ci.integration-test.oci-test
  "End-to-end test that builds from Github on OCI"
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [commands :as cmd]
             [config :as config]
             [runtime :as rt]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.web.auth :as auth]))

(defn- maybe-deref [x]
  (cond-> x
    (md/deferred? x) deref))

(deftest oci-build
  ;; Mimic behaviour using OCI runner
  (let [conf (-> (config/load-config-file "dev-resources/test/config/oci-build-config.edn")
                 (assoc-in [:build :git :dir] (str "test-" (System/currentTimeMillis)))
                 (assoc :work-dir "tmp"))]
    (testing "config contains necessary info"
      (is (not-empty (:jwk conf)))
      (is (not-empty (:build conf)))
      (is (not-empty (:api conf))))

    (testing "config defines components"
      (is (= :oci (get-in conf [:runner :type])))
      (is (= :jms (get-in conf [:events :type]))))

    (testing "can fetch build params using api token"
      (is (string? (get-in conf [:jwk :private-key])))
      (let [kp (auth/config->keypair conf)
            jwt (->> (:build conf)
                     (b/sid)
                     (auth/build-token)
                     (auth/generate-jwt-from-rt {:jwk kp}))]
        (is (string? jwt))
        (is (some? @(bas/get-params-from-api (assoc (:api conf) :token jwt)
                                             (:build conf))))))
    
    #_(testing "can checkout code and run script in OCI containers"
      (is (= 0 (maybe-deref (cmd/run-build conf)))))))
