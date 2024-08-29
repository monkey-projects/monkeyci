(ns monkey.ci.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as mc]
             [oci :as oci]
             [runners :as r]
             [sid :as sid]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.helpers :as h]
            [monkey.oci.container-instance.core :as ci]
            [monkey.ci.test.runtime :as trt]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci))))

  (testing "creates oci runner"
    (is (fn? (r/make-runner {:runner {:type :oci}})))))

(deftest oci-runner
  (let [build {:git {:url "test-url"
                     :branch "main"}}]
    (testing "runs container instance"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:exit-code 0}]}}))]
        (is (= 0 (-> (sut/oci-runner {} {} build {})
                     (deref))))))))

(defn- parse-config-vol [ic]
  (-> (oci/find-volume ic sut/config-volume)
      :configs
      first
      :data
      h/base64->
      u/parse-edn-str))

(deftest instance-config
  (let [priv-key (h/generate-private-key)
        build {:build-id "test-build-id"
               :sid ["a" "b" "test-build-id"]
               :git {:url "http://git-url"
                     :branch "main"
                     :commit-id "test-commit"
                     :ssh-keys [{:private-key "test-privkey"
                                 :public-key "test-pubkey"}]}}
        rt (trt/test-runtime)
        conf {:availability-domain "test-ad"
              :compartment-id "test-compartment"
              :image-pull-secrets "test-secrets"
              :vnics "test-vnics"
              :image-url "test-image"
              :image-tag "test-version"
              :credentials {:private-key priv-key}}
        inst (sut/instance-config conf build rt)]

    (testing "creates display name from build info"
      (is (= "test-build-id" (:display-name inst))))

    (testing "sets tags from sid"
      (is (= {"customer-id" "a"
              "repo-id" "b"}
             (:freeform-tags inst))))

    (testing "merges with configured tags"
      (let [inst (sut/instance-config (assoc conf :freeform-tags {"env" "test"})
                                      build
                                      rt)]
        (is (= "a" (get-in inst [:freeform-tags "customer-id"])))
        (is (= "test" (get-in inst [:freeform-tags "env"])))))

    (testing "fails when no git url specified"
      (is (thrown? Exception (sut/instance-config conf (update build :git dissoc :url) rt))))

    (testing "fails when no git branch or commit-id specified"
      (is (thrown? Exception (sut/instance-config conf
                                                  (update build :git dissoc :branch :commit-id)
                                                  rt))))

    (testing "container"
      (let [c (first (:containers inst))]
        
        (testing "uses configured image tag"
          (is (= "test-tag"
                 (-> conf
                     (assoc :image-tag "test-tag")
                     (sut/instance-config build rt)
                     :containers
                     first
                     :image-url
                     (.split ":")
                     last))))
        
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

        (testing "config"
          (testing "passed at global config path"
            (let [mnt (oci/find-mount c sut/config-volume)]
              (is (some? mnt))
              (is (= "/etc/monkeyci" (:mount-path mnt)))))

          (let [vol (oci/find-volume inst sut/config-volume)
                parsed (-> vol
                           :configs
                           first
                           :data
                           h/base64->
                           u/parse-edn-str)]
            (testing "contains `config.edn`"
              (is (= 1 (count (:configs vol))))
              (is (= "config.edn" (:file-name (first (:configs vol))))))

            (testing "enforces child runner"
              (is (= :child (get-in parsed [:runner :type]))))
            
            (testing "adds api token"
              (is (not-empty (get-in parsed [:api :token]))))

            (testing "api token contains serialized build sid in sub"
              (is (= (sid/serialize-sid (:sid build))
                     (-> parsed
                         (get-in [:api :token])
                         (h/parse-token-payload)
                         :sub))))

            (testing "adds build sid"
              (is (= (:sid build) (get-in parsed [:build :sid]))))))))

    (testing "ssh keys"
      (testing "adds as volume"
        (let [vol (oci/find-volume inst sut/ssh-keys-volume)]
          (is (some? vol))
          (is (= ["key-0" "key-0.pub"]
                 (->> vol
                      :configs
                      (map :file-name))))))

      (let [cc (first (:containers inst))
            mnt (oci/find-mount cc sut/ssh-keys-volume)
            conf (parse-config-vol inst)]
        (testing "mounts in container"
          (is (some? mnt)))

        (testing "adds ssh keys dir in config"
          (is (= (:mount-path mnt)
                 (get-in conf [:build :git :ssh-keys-dir]))))))

    (testing "log config"
      (h/with-tmp-dir dir
        (let [log-path (io/file dir "logback-test.xml")
              rt (assoc-in rt [:config :runner :log-config] (.getAbsolutePath log-path))
              _ (spit log-path "test log contents")
              inst (sut/instance-config conf build rt)
              vol (oci/find-volume inst sut/log-config-volume)]
          
          (testing "adds as volume"
            (is (some? vol))
            (is (sequential? (:configs vol))))

          (let [cc (first (:containers inst))
                mnt (oci/find-mount cc sut/log-config-volume)
                conf (parse-config-vol inst)]
            (testing "mounts in container"
              (is (some? mnt)))

            (testing "adds config file path as env var"
              (is (= (str (:mount-path mnt) "/" (-> vol :configs first :file-name))
                     (get-in conf [:runner :log-config]))))))))

    (testing "events config"
      (testing "taken from top level"
        (let [rt (assoc-in rt [:config :events :client :address] "test-addr")
              inst (sut/instance-config conf build rt)
              conf (parse-config-vol inst)]
          (is (= "test-addr" (get-in conf [:events :client :address])))))

      (testing "merges with runner specific config"
        (let [rt (-> rt
                     (assoc-in [:config :events] {:type :jms
                                                  :client {:address "test-addr"
                                                           :username "test-user"}})
                     (assoc-in [:config :runner :events :client :address] "runner-addr"))
              inst (sut/instance-config conf build rt)
              conf (parse-config-vol inst)]
          (is (= "runner-addr" (get-in conf [:events :client :address])))
          (is (= "test-user" (get-in conf [:events :client :username])))
          (is (= :jms (get-in conf [:events :type]))))))))
 
(deftest wait-for-script-end-event
  (testing "returns a deferred that holds the script end event"
    (let [events (ec/make-events {:type :manifold})
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
               :image-tag))))

  (testing "formats using app version if format string specified"
    (is (= (str "format-" (mc/version))
           (-> {:runner {:type :oci
                         :image-tag "format-%s"}}
               (r/normalize-runner-config)
               :runner
               :image-tag)))))
