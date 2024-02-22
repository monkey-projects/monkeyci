(ns monkey.ci.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [manifold.deferred :as md]
            [monkey.ci.oci :as sut]
            [monkey.ci.helpers :as h]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os.stream :as os])
  (:import java.io.ByteArrayInputStream))

(deftest group-credentials
  (testing "takes credentials from env"
    (is (= {:credentials {:fingerprint "test"}}
           (sut/group-credentials {:credentials-fingerprint "test"}))))

  (testing "merges credentials from env"
    (is (= {:credentials {:fingerprint "test"
                          :other "orig"}}
           (sut/group-credentials {:credentials-fingerprint "test"
                                   :credentials {:other "orig"}}))))
  
  (testing "overwrites from env"
    (is (= {:credentials {:fingerprint "new"}}
           (sut/group-credentials {:credentials {:fingerprint "old"}
                                   :credentials-fingerprint "new"}))))

  (testing "keeps as-is when no env"
    (is (= {:credentials {:fingerprint "test"}}
           (sut/group-credentials {:credentials {:fingerprint "test"}})))))

(deftest normalize-config
  (testing "combines oci and specific config from conf"
    (is (= {:region "eu-frankfurt-1"
            :bucket-name "test-bucket"}
           (-> (sut/normalize-config
                {:oci {:region "eu-frankfurt-1"}
                 :storage {:bucket-name "test-bucket"}}
                :storage)
               :storage
               (select-keys [:region :bucket-name])))))

  (testing "loads private key from file"
    (is (instance? java.security.PrivateKey
                   (-> (sut/normalize-config
                        {:storage
                         {:credentials
                          {:private-key "dev-resources/test/test-key.pem"}}}
                        :storage)
                       :storage
                       :credentials
                       :private-key)))))

(deftest stream-to-bucket
  (testing "pipes input stream to multipart"
    (with-redefs [os/input-stream->multipart (fn [ctx opts]
                                               {:context ctx
                                                :opts opts})]
      (let [in (ByteArrayInputStream. (.getBytes "test stream"))
            r (sut/stream-to-bucket {:key "value"} in)]
        (is (some? (:context r)))
        (is (= in (get-in r [:opts :input-stream])))))))

(deftest wait-for-completion
  (testing "returns channel that holds zero on successful completion"
    (let [ch (sut/wait-for-completion
              :test-client
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

(deftest run-instance
  (testing "creates container instance"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (fn [_ opts]
                                                   (swap! calls conj opts)
                                                   {:status 500})]
        (is (some? (sut/run-instance {} {})))
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
        (is (some? (sut/run-instance {} {})))
        (is (not= :timeout (h/wait-until #(pos? (count @calls)) 200))))))

  (testing "when creation fails, does not poll state"
    (let [calls (atom [])]
      (with-redefs [ci/create-container-instance (constantly {:status 400
                                                              :body
                                                              {:message "test error"}})
                    ci/get-container-instance (fn [_ opts]
                                                (swap! calls conj opts)
                                                nil)]
        (is (some? (sut/run-instance {} {})))
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
                      (md/success-deferred
                       {:status 200
                        :body
                        {:lifecycle-state "INACTIVE"
                         :containers [{:display-name "build"
                                       :container-id cid}]}}))
                    ci/get-container
                    (fn [_ opts]
                      (md/success-deferred
                       (if (= cid (:container-id opts))
                         {:status 200
                          :body
                          {:exit-code exit}}
                         {:status 400
                          :body
                          {:message "Invalid container id"}})))]
        (is (= exit (deref (sut/run-instance {} {}) 200 :timeout))))))

  (testing "returns configured container exit code"
    (let [cid (random-uuid)
          exit 545
          opts {:match-container (partial filter (comp (partial = "job") :display-name))}
          containers [{:display-name "sidecar"
                       :container-id (random-uuid)}
                      {:display-name "job"
                       :container-id cid}]]
      (with-redefs [ci/create-container-instance
                    (constantly
                     (md/success-deferred
                      {:status 200
                       :body
                       {:id "test-instance"
                        :containers containers}}))
                    sut/wait-for-completion
                    (fn [_ opts]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:lifecycle-state "INACTIVE"
                         :containers containers}}))
                    ci/get-container
                    (fn [_ opts]
                      (md/success-deferred
                       (if (= cid (:container-id opts))
                         {:status 200
                          :body
                          {:exit-code exit}}
                         {:status 400
                          :body
                          {:message "Invalid container id"}})))]
        (is (= exit (deref (sut/run-instance {} {} opts) 200 :timeout))))))

  (testing "deletes instance if configured"
    (let [exit 545
          iid (random-uuid)
          containers [{:display-name "test-container"
                       :container-id (random-uuid)}]
          deleted? (atom false)]
      (with-redefs [ci/create-container-instance
                    (constantly
                     (md/success-deferred
                      {:status 200
                       :body
                       {:id iid
                        :containers containers}}))
                    sut/wait-for-completion
                    (fn [_ opts]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:lifecycle-state "INACTIVE"
                         :containers containers}}))
                    ci/get-container
                    (fn [_ opts]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:exit-code exit
                         :container-instance-id iid}}))
                    ci/delete-container-instance
                    (fn [_ opts]
                      (reset! deleted? true)
                      (md/success-deferred
                       {:status (if (= iid (:instance-id opts)) 200 400)}))]
        (is (= exit (deref (sut/run-instance {} {} {:delete? true}) 200 :timeout)))
        (is (true? @deleted?)))))
  
  (testing "does not instance if configured but not created"
    (let [exit 545
          iid (random-uuid)
          containers [{:display-name "test-container"
                       :container-id (random-uuid)}]
          deleted? (atom false)]
      (with-redefs [ci/create-container-instance
                    (constantly
                     (md/success-deferred
                      {:status 404
                       :body
                       {:message "test failure"}}))
                    ci/delete-container-instance
                    (fn [_ opts]
                      (reset! deleted? true)
                      (md/success-deferred
                       {:status (if (= iid (:instance-id opts)) 200 400)}))]
        (is (= 1 (deref (sut/run-instance {} {} {:delete? true}) 200 :timeout)))
        (is (false? @deleted?))))))

(deftest instance-config
  (let [conf {:availability-domain "test-ad"
              :compartment-id "test-compartment"
              :image-pull-secrets "test-secrets"
              :vnics "test-vnics"
              :image-url "test-image"
              :image-tag "test-version"}
        inst (sut/instance-config conf)]

    (testing "uses settings from config"
      (is (= "test-ad" (:availability-domain inst)))
      (is (= "test-compartment" (:compartment-id inst))))

    (testing "picks random availability domain if multiple given"
      (let [ads ["ad-1" "ad-2" "ad-3"]
            c (-> conf
                  (assoc :availability-domains ads)
                  (dissoc :availability-domain))
            inst (sut/instance-config c)
            ad (:availability-domain inst)]
        (is (string? ad))
        (is (some? ((set ads) ad)))))

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

    (testing "container"
      (is (= 1 (count (:containers inst))) "there should be exactly one")
      
      (let [c (first (:containers inst))]
        
        (testing "uses configured image and tag"
          (is (= "test-image:test-version" (:image-url c))))

        (let [vol-mounts (->> (:volume-mounts c)
                              (group-by :volume-name))]
          
          (testing "mounts checkout dir"
            (is (= {:mount-path "/opt/monkeyci/checkout"
                    :is-read-only false
                    :volume-name "checkout"}
                   (first (get vol-mounts "checkout"))))))))))
