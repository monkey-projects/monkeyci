(ns monkey.ci.runners.oci3-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci.runners.oci3 :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.ci.storage :as st]
            [monkey.oci.container-instance.core :as ci]
            [monkey.ci.helpers :as h]
            [monkey.mailman.core :as mmc]))

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
          (is (= {:container-instance ::test-config}
                 (-> @inv
                     first
                     :config)))
          (is (= ::test-client
                 (-> @inv
                     first
                     :client)))
          (is (= {:status 200}
                 (sut/get-ci-response r)))))

      (testing "fails if creation fails"))))

(deftest initialize-build
  (testing "returns `build/initializing` event"
    (is (= :build/initializing
           (-> {:event {:build {:sid ::test-build}}}
               (sut/initialize-build)
               :type)))))

(def build->sid (apply juxt st/build-sid-keys))

(deftest make-router
  (let [build (h/gen-build)]
    
    (testing "`build/pending`"
      (testing "returns `build/initializing` event"
        (let [fake-start-ci {:name ::sut/start-ci
                             :enter (fn [ctx]
                                      (sut/set-ci-response ctx {:status 200
                                                                :body {:id "test-instance"}}))}
              router (-> (sut/make-router {})
                         (mmc/replace-interceptors [fake-start-ci]))

              r (router {:type :build/pending
                         :sid (build->sid build)
                         :build build})
              res (-> r
                      first
                      :result
                      first)]
          (is (spec/valid? ::se/event res))
          (is (= :build/initializing (:type res)))))

      (testing "when instance creation fails, returns `build/end` event"
        (let [fail-start-ci {:name ::sut/start-ci
                             :enter (fn [ctx]
                                      (sut/set-ci-response ctx {:status 500
                                                                :body {:id "test-instance"}}))}
              router (-> (sut/make-router {})
                         (mmc/replace-interceptors [fail-start-ci]))

              r (router {:type :build/pending
                         :sid (build->sid build)
                         :build build})
              res (-> r
                      first
                      :result
                      first)]
          (is (spec/valid? ::se/event res))
          (is (= :build/end (:type res)))
          (is (= :error (-> res :build :status))))))))
