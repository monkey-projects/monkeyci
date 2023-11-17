(ns monkey.ci.test.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as mc]
             [events :as e]
             [runners :as r]]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.test.helpers :as h]
            [monkey.oci.container-instance.core :as ci]))

(deftest make-runner
  (testing "provides for `:oci` type"
    (is (some? (get-method r/make-runner :oci))))

  (testing "creates oci runner"
    (is (fn? (r/make-runner {:runner {:type :oci}})))))

(deftest oci-runner
  (testing "creates container instance"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (fn [_ opts]
                                                   (swap! calls conj opts)
                                                   {:status 500})]
        (is (some? (sut/oci-runner {} {} {})))
        (is (not= :timeout (h/wait-until #(pos? (count @calls)) 200)))
        (is (some? (:container-instance (first @calls)))))))

  (testing "when started, polls state"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (constantly {:status 200
                                                              :body
                                                              {:id "test-instance"}})
                    sut/wait-for-completion (fn [_ opts]
                                              (swap! calls conj opts)
                                              nil)]
        (is (some? (sut/oci-runner {} {} {})))
        (is (not= :timeout (h/wait-until #(pos? (count @calls)) 200))))))

  (testing "when creation fails, does not poll state"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (constantly {:status 400
                                                              :body
                                                              {:message "test error"}})
                    ci/get-container-instance (fn [_ opts]
                                                (swap! calls conj opts)
                                                nil)]
        (is (some? (sut/oci-runner {} {} {})))
        (is (zero? (count @calls))))))

  (testing "returns build container exit code"
    (let [cid (random-uuid)
          exit 543]
      (with-redefs [ci/create-container-instance
                    (constantly
                     (md/success-deferred
                      {:status 200
                       :body
                       {:id "test-instance"
                        :containers
                        [{:display-name "build"
                          :container-id cid}]}}))
                    sut/wait-for-completion
                    (fn [_ opts]
                      (println "Waiting for completion:" opts)
                      (md/success-deferred
                       {:status 200
                        :body
                        {:lifecycle-state "INACTIVE"
                         :containers [{:display-name "build"
                                       :container-id cid}]}}))
                    ci/get-container
                    (fn [_ opts]
                      (println "Retrieving container details for" opts)
                      (md/success-deferred
                       (if (= cid (:container-id opts))
                         {:status 200
                          :body
                          {:exit-code exit}}
                         {:status 400
                          :body
                          {:message "Invalid container id"}})))]
        (is (= exit (h/try-take (sut/oci-runner {} {} {}) 200 :timeout))))))

  (testing "launches `:build/completed` event"
    (h/with-bus
      (fn [bus]
        (with-redefs [ci/create-container-instance (fn [_ opts]
                                                     {:status 500})]
          (let [received (atom [])
                h (e/register-handler bus :build/completed (partial swap! received conj))]
            (is (some? (sut/oci-runner {} {} {:event-bus bus})))
            (is (not= :timeout (h/wait-until #(pos? (count @received)) 1000)))
            (is (= 1 (count @received)))))))))

(deftest instance-config
  (let [ctx {:build {:build-id "test-build-id"
                     :sid ["a" "b" "c" "test-build-id"]
                     :git {:url "http://git-url"
                           :branch "main"
                           :id "test-commit"}}}
        conf {:availability-domain "test-ad"
              :compartment-id "test-compartment"
              :image-pull-secrets "test-secrets"
              :vnics "test-vnics"
              :image-url "test-image"
              :image-tag "test-version"}
        inst (sut/instance-config conf ctx)]

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
                                 (sut/instance-config ctx)
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

        (testing "mounts checkout dir"
          (is (= [{:mount-path "/opt/monkeyci/checkout"
                   :is-read-only false
                   :volume-name "checkout"}]
                 (:volume-mounts c))))

        (let [env (:environment-variables c)]
          (testing "passes config as env vars"
            (is (map? env))
            (is (not-empty env)))

          (testing "env vars are strings, not keywords"
            (is (every? string? (keys env)))))))))

(deftest wait-for-completion
  (testing "returns channel that holds zero on successful completion"
    (let [ch (sut/wait-for-completion :test-client
                                      {:get-details (fn [_ args]
                                                      (future
                                                        {:status 200
                                                         :body
                                                         {:lifecycle-state "INACTIVE"}}))})]
      (is (some? ch))
      (is (map? @(md/timeout! ch 200 :timeout)))))

  (testing "loops until a final state is encountered"
    (let [results (->> ["CREATING" "ACTIVE" "INACTIVE"]
                       (ca/to-chan!)
                       vector
                       (ca/map (fn [s]
                                 {:status 200
                                  :body {:lifecycle-state s}})))
          ch (sut/wait-for-completion :test-client
                                      {:get-details (fn [& _]
                                                      (future (ca/<!! results)))
                                       :poll-interval 100})]
      (is (map? @(md/timeout! ch 1000 :timeout)))))

  (testing "returns last response on completion"
    (let [r {:status 200
             :body {:lifecycle-state "FAILED"}}
          ch (sut/wait-for-completion :test-client
                                      {:get-details (fn [_ args]
                                                      (future r))})]
      (is (some? ch))
      (is (= r @(md/timeout! ch 200 :timeout))))))
