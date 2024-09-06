(ns monkey.ci.integration-test.oci-test
  "End-to-end test that builds from Github on OCI"
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci
             [commands :as cmd]
             [config :as config]
             [runtime :as rt]]))

(defn- maybe-deref [x]
  (cond-> x
    (md/deferred? x) deref))

(deftest oci-build
  (testing "checkout code and run script in OCI containers"
    ;; Mimic behaviour using OCI runner
    (let [conf (-> (config/load-config-file "dev-resources/test/config/oci-build-config.edn")
                   (assoc-in [:build :git :dir] (str "test-" (System/currentTimeMillis)))
                   (config/normalize-config {} {:workdir "tmp"}))]
      (is (some? (:build conf)))
      (is (= :oci (get-in conf [:runner :type])))
      (is (= :jms (get-in conf [:events :type])))
      (is (= 0 (maybe-deref (cmd/run-build conf)))))))
