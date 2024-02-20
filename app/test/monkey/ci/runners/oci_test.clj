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
                                                 {:status 500})]
      (let [received (atom [])]
        (is (some? (sut/oci-runner {} {} {:events {:poster (partial swap! received conj)}})))
        (is (not-empty @received))
        (is (= :build/completed (-> @received first :type)))))))

(deftest instance-config
  (let [priv-key (h/generate-private-key)
        rt {:build {:build-id "test-build-id"
                    :sid ["a" "b" "c" "test-build-id"]
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

    (testing "uses settings from context"
      (is (= "test-ad" (:availability-domain inst)))
      (is (= "test-compartment" (:compartment-id inst)))
      (is (= "test-build-id" (:display-name inst))))

    (testing "never restart"
      (is (= "NEVER" (:container-restart-policy inst))))
    
    (testing "uses ARM shape"
      (is (= "CI.Standard.A1.Flex" (:shape inst)))
      (let [{cpu :ocpus
             mem :memory-in-g-bs} (:shape-config inst)]
        (is (pos? cpu))
        (is (pos? mem))))

    (testing "uses pull secrets from config"
      (is (= "test-secrets" (:image-pull-secrets inst))))

    (testing "uses vnics from config"
      (is (= "test-vnics" (:vnics inst))))

    (testing "adds work volume"
      (is (= {:name "checkout"
              :volume-type "EMPTYDIR"
              :backing-store "EPHEMERAL_STORAGE"}
             (first (:volumes inst)))))

    (testing "adds private key as config volume"
      (let [v (second (:volumes inst))
            c (first (:configs v))]
        (is (= "private-key" (:name v)))
        (is (= "CONFIGFILE" (:volume-type v)))
        (is (= 1 (count (:configs v))))
        (is (string? (:data c)))
        (is (= "privkey" (:file-name c)))))

    (testing "does not add priv key when none specified"
      (is (= 1 (-> conf
                   (dissoc :credentials)
                   (sut/instance-config rt)
                   :volumes
                   (count)))))

    (testing "sets tags from sid"
      (is (= {"customer-id" "a"
              "project-id" "b"
              "repo-id" "c"}
             (:freeform-tags inst))))

    (testing "container"
      (is (= 1 (count (:containers inst))) "there should be exactly one")
      
      (let [c (first (:containers inst))]
        
        (testing "uses configured image and tag"
          (is (= "test-image:test-version" (:image-url c))))

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
                  "--sid" "a/b/c/test-build-id"
                  "-u" "http://git-url"
                  "-b" "main"
                  "--commit-id" "test-commit"]
                 (:arguments c))))

        (let [vol-mounts (->> (:volume-mounts c)
                              (group-by :volume-name))]
          
          (testing "mounts checkout dir"
            (is (= {:mount-path "/opt/monkeyci/checkout"
                    :is-read-only false
                    :volume-name "checkout"}
                   (first (get vol-mounts "checkout")))))

          (testing "mounts private key"
            (is (= {:mount-path "/opt/monkeyci/keys"
                    :is-read-only true
                    :volume-name "private-key"}
                   (first (get vol-mounts "private-key"))))))

        (let [env (:environment-variables c)]
          (testing "passes config as env vars"
            (is (map? env))
            (is (not-empty env)))

          (testing "env vars are strings, not keywords"
            (is (every? string? (keys env))))

          (testing "points oci private key to mounted file"
            (is (= "/opt/monkeyci/keys/privkey"
                   (get env "monkeyci-oci-credentials-private-key"))))))

      (testing "drops nil env vars"
        (let [c (-> rt
                    (assoc-in [:build :pipeline] nil)
                    (as-> x (sut/instance-config conf x)))]
          (is (not (contains? (-> c :containers first :environment-variables) "monkeyci-build-pipeline"))))))))
 

