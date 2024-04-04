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
            [monkey.ci.events.core :as ec]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.helpers :as h]
            [monkey.oci.container-instance.core :as ci]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci))))

  (testing "creates oci runner"
    (is (fn? (r/make-runner {:runner {:type :oci}})))))

(deftest oci-runner
  (let [rt {:build {:git {:url "test-url"
                          :branch "main"}}}]
    (testing "runs container instance"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:exit-code 0}]}}))]
        (is (= 0 (-> (sut/oci-runner {} {} rt)
                     (deref))))))))

(deftest instance-config
  (let [priv-key (h/generate-private-key)
        rt (assoc (h/test-rt)
                  :build {:build-id "test-build-id"
                          :sid ["a" "b" "test-build-id"]
                          :git {:url "http://git-url"
                                :branch "main"
                                :commit-id "test-commit"
                                :ssh-keys [{:private-key "test-privkey"
                                            :public-key "test-pubkey"}]}})
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

    (testing "fails when no git url specified"
      (is (thrown? Exception (sut/instance-config conf (update-in rt [:build :git] dissoc :url)))))

    (testing "fails when no git branch or commit-id specified"
      (is (thrown? Exception (sut/instance-config conf (update-in rt [:build :git] dissoc :branch :commit-id)))))

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
            (is (every? string? (keys env))))

          (testing "enforces child runner"
            (is (= "child" (get env "monkeyci-runner-type"))))

          (testing "adds api token"
            (is (not-empty (get env "monkeyci-api-token"))))))

      (testing "drops nil env vars"
        (let [c (-> rt
                    (assoc-in [:build :pipeline] nil)
                    (as-> x (sut/instance-config conf x)))]
          (is (not (contains? (-> c :containers first :environment-variables) "monkeyci-build-pipeline"))))))

    (testing "ssh keys"
      (testing "adds as volume"
        (let [vol (oci/find-volume inst sut/ssh-keys-volume)]
          (is (some? vol))
          (is (= ["key-0" "key-0.pub"]
                 (->> vol
                      :configs
                      (map :file-name))))))

      (let [cc (first (:containers inst))
            mnt (oci/find-mount cc sut/ssh-keys-volume)]
        (testing "mounts in container"
          (is (some? mnt)))

        (testing "adds ssh keys dir as env var"
          (is (= (:mount-path mnt)
                 (get-in cc [:environment-variables "monkeyci-build-git-ssh-keys-dir"]))))))

    (testing "log config"
      (h/with-tmp-dir dir
        (let [log-path (io/file dir "logback-test.xml")
              rt (assoc-in rt [:config :runner :log-config] (.getAbsolutePath log-path))
              _ (spit log-path "test log contents")
              inst (sut/instance-config conf rt)
              vol (oci/find-volume inst sut/log-config-volume)]
          
          (testing "adds as volume"
            (is (some? vol))
            (is (sequential? (:configs vol))))

          (let [cc (first (:containers inst))
                mnt (oci/find-mount cc sut/log-config-volume)]
            (testing "mounts in container"
              (is (some? mnt)))

            (testing "adds config file path as env var"
              (is (= (str (:mount-path mnt) "/" (-> vol :configs first :file-name))
                     (get-in cc [:environment-variables "monkeyci-runner-log-config"]))))))))

    (testing "events config"
      (testing "taken from top level"
        (let [rt (assoc-in rt [:config :events :client :address] "test-addr")
              inst (sut/instance-config conf rt)
              env (-> inst :containers first :environment-variables)]
          (is (= "test-addr" (get env "monkeyci-events-client-address")))))

      (testing "overwrites with runner specific config"
        (let [rt (-> rt
                     (assoc-in [:config :events :client :address] "test-addr")
                     (assoc-in [:config :runner :events :client :address] "runner-addr"))
              inst (sut/instance-config conf rt)
              env (-> inst :containers first :environment-variables)]
          (is (= "runner-addr" (get env "monkeyci-events-client-address"))))))))
 
(deftest wait-for-script-end-event
  (testing "returns a deferred that holds the script end event"
    (let [events (ec/make-events {:events {:type :manifold}})
          sid (repeatedly 3 random-uuid)
          d (sut/wait-for-script-end-event events sid)]
      (is (md/deferred? d))
      (is (not (md/realized? d)) "should not be realized initially")
      (is (some? (ec/post-events events [{:type :script/start
                                          :sid sid}])))
      (is (not (md/realized? d)) "should not be realized after start event")
      (is (some? (ec/post-events events [{:type :script/end
                                          :sid sid}])))
      (is (= :script/end (-> (deref d 100 :timeout)
                              :type))))))

(deftest normalize-runner-config
  (testing "uses configured image tag"
    (is (= "test-image"
           (-> {:runner {:type :oci
                         :image-tag "test-image"}}
               (r/normalize-runner-config)
               :runner
               :image-tag))))

  (testing "uses app version if no image tag configured"
    (is (= (mc/version)
           (-> {:runner {:type :oci}}
               (r/normalize-runner-config)
               :runner
               :image-tag)))))
