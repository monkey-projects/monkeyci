(ns monkey.ci.runners.oci3-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runners.oci3 :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.oci.container-instance.core :as ci]
            [monkey.ci.helpers :as h]
            [monkey.mailman.core :as mmc]))

#_(deftest ci-base-config
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

(deftest prepare-ci-config
  (let [{:keys [enter] :as i} (sut/prepare-ci-config {:private-key (h/generate-private-key)})]
    (is (keyword? (:name i)))
    
    (testing "updates ci config with container details"
      (let [r (enter {})]
        (is (= 2 (-> (sut/get-ci-config r)
                     :containers
                     count)))))))

(deftest save-runner-details
  (let [{:keys [enter]:as i} sut/save-runner-details]
    (is (keyword? (:name i)))
    
    (testing "`enter` saves ci results in db"
      (h/with-memory-store st
        (let [sid (repeatedly 3 cuid/random-cuid)
              ctx (-> {:event {:sid sid}}
                      (sut/set-ci-response {:status 200
                                            :body {:id "test-ocid"}})
                      (em/set-db st))
              r (enter ctx)]
          (is (= ctx r))
          (is (= {:runner :oci
                  :details {:instance-id "test-ocid"}}
                 (st/find-runner-details st sid))))))))

(deftest load-runner-details
  (let [{:keys [enter]:as i} sut/load-runner-details]
    (is (keyword? (:name i)))
    
    (testing "`enter` fetches runner details from db"
      (h/with-memory-store st
        (let [sid (repeatedly 3 cuid/random-cuid)
              ctx (-> {:event {:sid sid}}
                      (em/set-db st))
              details {:runner :oci
                       :details {:instance-id (random-uuid)}}
              _ (st/save-runner-details st sid details)
              r (enter ctx)]
          (is (= details
                 (sut/get-runner-details r))))))))

(deftest initialize-build
  (testing "returns `build/initializing` event"
    (is (= :build/initializing
           (-> {:event {:build {:sid ::test-build}}}
               (sut/initialize-build)
               :type)))))

(deftest delete-instance
  (testing "deletes container instance according to runner details"
    (h/with-memory-store st
      (let [build (h/gen-build)
            sid (st/ext-build-sid build)
            ocid (random-uuid)
            ctx (-> {:event {:sid sid
                             :type :build/end
                             :build build}}
                    (em/set-db st)
                    (sut/set-runner-details {:details {:instance-id ocid}}))
            deleted (atom nil)]
        (with-redefs [ci/delete-container-instance (fn [client opts]
                                                     (reset! deleted opts)
                                                     (md/success-deferred {:status 200}))]
          (is (nil? (sut/delete-instance ::test-client ctx)))
          (is (= {:instance-id ocid} @deleted)))))))

(deftest make-router
  (let [build (h/gen-build)
        st (st/make-memory-storage)
        conf {:private-key (h/generate-private-key)}]
    
    (testing "`build/pending`"
      (testing "returns `build/initializing` event"
        (let [fake-start-ci {:name ::sut/start-ci
                             :enter (fn [ctx]
                                      (sut/set-ci-response ctx {:status 200
                                                                :body {:id "test-instance"}}))}
              router (-> (sut/make-router conf st)
                         (mmc/replace-interceptors [fake-start-ci]))

              r (router {:type :build/pending
                         :sid (st/ext-build-sid build)
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
              router (-> (sut/make-router conf st)
                         (mmc/replace-interceptors [fail-start-ci]))

              r (router {:type :build/pending
                         :sid (st/ext-build-sid build)
                         :build build})
              res (-> r
                      first
                      :result
                      first)]
          (is (spec/valid? ::se/event res))
          (is (= :build/end (:type res)))
          (is (= :error (-> res :build :status))))))))
