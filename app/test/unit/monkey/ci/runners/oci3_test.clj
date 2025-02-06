(ns monkey.ci.runners.oci3-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold.deferred :as md]
            [monkey.ci.runners.oci3 :as sut]
            [monkey.oci.container-instance.core :as ci]))

(deftest ci-base-config
  (testing "`enter` adds base container instance config to context"
    (let [{:keys [enter] :as i} (sut/ci-base-config {})]
      (is (keyword? (:name i)))
      (is (map? (-> {}
                    (enter)
                    (sut/get-ci-config)))))))

(deftest start-ci
  (let [{:keys [enter] :as i} (sut/start-ci ::test-client)
        inv (atom [])]
    (is (keyword? (:name i)))

    (with-redefs [ci/create-container-instance (fn [client ic]
                                                 (swap! inv conj {:client client
                                                                  :config ic})
                                                 (md/success-deferred {:status 200}))]
      (testing "`enter` starts container instance"
        (let [r (-> {}
                    (sut/set-ci-config ::test-config)
                    (enter))]
          (is (= ::test-config (-> @inv
                                   first
                                   :config)))
          (is (= ::test-client (-> @inv
                                   first
                                   :client)))
          (is (= {:status 200} (sut/get-ci-response r))))))))
