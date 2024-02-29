(ns monkey.ci.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as mc]
             [oci :as oci]
             [runners :as r]]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.helpers :as h]
            [monkey.oci.container-instance.core :as ci]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci))))

  (testing "creates oci runner"
    (is (fn? (r/make-runner {:runner {:type :oci}})))))

(deftest oci-runner
  (testing "runs container instance"
    (with-redefs [oci/run-instance (constantly (md/success-deferred 0))]
      (is (= 0 (-> (sut/oci-runner {} {} {})
                   (deref))))))

  (testing "launches `:build/completed` event"
    (with-redefs [ci/create-container-instance (fn [_ opts]
                                                 {:status 500})
                  ci/delete-container-instance (fn [_ r] r)]
      (let [received (atom [])]
        (is (some? (sut/oci-runner {} {} {:events {:poster (partial swap! received conj)}})))
        (is (not-empty @received))
        (is (= :build/completed (-> @received first :type)))))))

(deftest instance-config
  (let [priv-key (h/generate-private-key)
        rt {:build {:build-id "test-build-id"
                    :sid ["a" "b" "test-build-id"]
                    :git {:url "http://git-url"
                          :branch "main"
                          :id "test-commit"}}}
        conf {:availability-domain "test-ad"
              :compartment-id "test-compartment"
              :image-pull-secrets "test-secrets"
              :vnics "test-vnics"
              :image-url "test-image"
              :image-tag "test-version"
              :credentials {:private-key priv-key}}
        inst (sut/instance-config conf rt)]

    (testing "creates display name from build info"
      (is (= "test-build-id" (:display-name inst))))

    (testing "sets tags from sid"
      (is (= {"customer-id" "a"
              "repo-id" "b"}
             (:freeform-tags inst))))

    (testing "merges with configured tags"
      (let [inst (sut/instance-config (assoc conf :freeform-tags {"env" "test"})
                                      rt)]
        (is (= "a" (get-in inst [:freeform-tags "customer-id"])))
        (is (= "test" (get-in inst [:freeform-tags "env"])))))

    (testing "container"
      (let [c (first (:containers inst))]
        
        (testing "uses app version if no tag configured"
          (is (cs/ends-with? (-> conf
                                 (dissoc :image-tag)
                                 (sut/instance-config rt)
                                 :containers
                                 first
                                 :image-url)
                             (mc/version))))
        
        (testing "configures basic properties"
          (is (string? (:display-name c))))

        (testing "provides arguments as to monkeyci build"
          (is (= ["-w" "/opt/monkeyci/checkout"
                  "build" "run"
                  "--sid" "a/b/test-build-id"
                  "-u" "http://git-url"
                  "-b" "main"
                  "--commit-id" "test-commit"]
                 (:arguments c))))

        (let [env (:environment-variables c)]
          (testing "passes config as env vars"
            (is (map? env))
            (is (not-empty env)))

          (testing "env vars are strings, not keywords"
            (is (every? string? (keys env))))))

      (testing "drops nil env vars"
        (let [c (-> rt
                    (assoc-in [:build :pipeline] nil)
                    (as-> x (sut/instance-config conf x)))]
          (is (not (contains? (-> c :containers first :environment-variables) "monkeyci-build-pipeline"))))))))
 

