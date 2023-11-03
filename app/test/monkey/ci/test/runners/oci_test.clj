(ns monkey.ci.test.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.runners :as r]
            [monkey.ci.runners.oci :as sut]
            [monkey.oci.container-instance.core :as ci]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci)))))

(deftest oci-runner
  (testing "creates container instance"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (fn [_ opts]
                                                   (swap! calls conj opts))]
        (is (some? (sut/oci-runner {} {} {})))
        (is (pos? (count @calls)))
        (is (some? (:container-instance (first @calls))))))))

(deftest instance-config
  (let [ctx {:build {:build-id "test-build-id"
                     :sid ["a" "b" "c" "test-build-id"]}}
        conf {:availability-domain "test-ad"
              :compartment-id "test-compartment"
              :image-pull-secrets "test-secrets"
              :vnics "test-vnics"
              :image-url "test-image"}
        inst (sut/instance-config conf ctx)]

    (testing "uses settings from context"
      (is (= "test-ad" (:availability-domain inst)))
      (is (= "test-compartment" (:compartment-id inst)))
      (is (= "test-build-id" (:display-name inst))))

    (testing "never restart"
      (is (= "NEVER" (:container-restart-policy inst))))
    
    (testing "uses ARM shape"
      (is (= "CI.Standard.A1.Flex" (:shape inst)))
      (is (= {:ocpus 1
              :memory-in-g-b-s 1}
             (:shape-config inst))))

    (testing "uses pull secrets from config"
      (is (= "test-secrets" (:image-pull-secrets inst))))

    (testing "uses vnics from config"
      (is (= "test-vnics" (:vnics inst))))

    (testing "adds work volume"
      (is (= {:name "checkout"
              :volume-type "EMPTYDIR"}
             (first (:volumes inst)))))

    (testing "container"
      (is (= 1 (count (:containers inst))) "there should be exactly one")
      
      (let [c (first (:containers inst))]
        
        (testing "uses configured image"
          (is (re-matches #"test-image:.+" (:image-url c))))
        
        (testing "configures basic properties"
          (is (string? (:display-name c))))

        (testing "provides arguments as to monkeyci build"
          (is (= ["-w" "/opt/monkeyci/checkout"
                  "build"
                  "--sid" "a/b/c/test-build-id"]
                 (:arguments c))))

        (testing "mounts checkout dir"
          (is (= [{:mount-path "/opt/monkeyci/checkout"
                   :is-read-only false
                   :volume-name "checkout"}]
                 (:volume-mounts c))))))))
