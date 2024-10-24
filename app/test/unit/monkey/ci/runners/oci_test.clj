(ns monkey.ci.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [oci :as oci]
             [runners :as r]
             [sid :as sid]
             [utils :as u]
             [version :as v]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h]
            [monkey.oci.container-instance.core :as ci]
            [monkey.ci.test.runtime :as trt]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci))))

  (testing "creates oci runner"
    (is (fn? (r/make-runner {:runner {:type :oci}})))))

(deftest oci-runner
  (let [build {:sid ["test-cust" "test-repo" "test-build"]
               :git {:url "test-url"
                     :branch "main"}}
        rt (trt/test-runtime)]
    (testing "runs container instance"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:exit-code 0}]}}))]
        (is (= 0 (-> (sut/oci-runner {} {} build rt)
                     (deref))))))

    (testing "posts `build/initializing` event"
      (with-redefs [oci/run-instance (constantly (md/success-deferred
                                                  {:status 200
                                                   :body {:containers [{:exit-code 0}]}}))]
        (is (some? (-> (sut/oci-runner {} {} build rt)
                       (deref))))
        (let [evt (->> rt
                       :events
                       (h/received-events)
                       (h/first-event-by-type :build/initializing))]
          (is (spec/valid? ::se/event evt))
          (is (= build (:build evt))))))

    (testing "posts `build/end` event on error"
      (with-redefs [oci/run-instance (constantly (md/error-deferred
                                                  {:status 500
                                                   :body {:message "test error"}}))]
        (is (some? (-> (sut/oci-runner {} {} build rt)
                       (deref))))
        (let [evt (->> rt
                       :events
                       (h/received-events)
                       (h/first-event-by-type :build/end))]
          (is (spec/valid? ::se/event evt))
          (is (= :error (:status evt))))))))

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
        rt (-> (trt/test-runtime)
               (trt/set-config
                {:api {:url "http://test-api"}
                 :artifacts {:type :disk}
                 :cache {:type :disk}
                 :build-cache {:type :disk}}))
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

    (testing "fails when no git branch, tag or commit-id specified"
      (is (thrown? Exception (sut/instance-config conf
                                                  (update build :git dissoc :branch :commit-id)
                                                  rt)))
      (is (some? (sut/instance-config conf
                                      (-> build
                                          (update :git dissoc :branch :commit-id)
                                          (assoc-in [:git :tag] "test-tag"))
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

            (testing "runner"
              (let [r (:runner parsed)]
                (testing "enforces in-container runner"
                  (is (= :in-container (:type r))))

                (testing "adds calculated credit multiplier"
                  (is (number? (:credit-multiplier r))))))
            
            (testing "adds api token"
              (is (not-empty (get-in parsed [:api :token]))))

            (testing "copies configured api"
              (is (some? (get-in parsed [:api :url]))))

            (testing "api token contains serialized build sid in sub"
              (is (= (sid/serialize-sid (:sid build))
                     (-> parsed
                         (get-in [:api :token])
                         (h/parse-token-payload)
                         :sub))))

            (testing "adds build sid"
              (is (= (:sid build) (get-in parsed [:build :sid]))))

            (testing "containes checkout-base-dir"
              (is (string? (:checkout-base-dir parsed))))

            (testing "contains `build-cache` configuration"
              (is (some? (:build-cache parsed))))))))

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
 
(deftest wait-for-build-end-event
  (testing "returns a deferred that holds the script end event"
    (let [events (ec/make-events {:type :manifold})
          sid (repeatedly 3 random-uuid)
          d (sut/wait-for-build-end-event events sid)]
      (is (md/deferred? d))
      (is (not (md/realized? d)) "should not be realized initially")
      (is (some? (ec/post-events events [{:type :script/start
                                          :sid sid}])))
      (is (not (md/realized? d)) "should not be realized after start event")
      (is (some? (ec/post-events events [{:type :build/end
                                          :sid sid}])))
      (is (= :build/end (-> (deref d 100 :timeout)
                            :type))))))

