(ns monkey.ci.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [manifold
             [deferred :as md]
             [stream :as ms]]
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

(deftest poll-for-completion
  (testing "returns channel that holds zero on successful completion"
    (let [ch (sut/poll-for-completion
              {:get-details (fn [_]
                              (future
                                {:status 200
                                 :body
                                 {:lifecycle-state "INACTIVE"}}))})]
      (is (some? ch))
      (is (map? @(md/timeout! ch 200 :timeout)))))

  (testing "loops until a final state is encountered"
    (let [results (->> ["CREATING" "ACTIVE" "INACTIVE"]
                       (map (fn [s]
                              {:status 200
                               :body {:lifecycle-state s}}))
                       (ms/->source))
          ch (sut/poll-for-completion {:get-details (fn [_]
                                                      (ms/take! results))
                                       :poll-interval 100})]
      (is (= "INACTIVE" (-> (md/timeout! ch 1000 :timeout)
                            deref
                            (get-in [:body :lifecycle-state]))))))
  
  (testing "stops polling when job container fails"
    (let [exit-codes [nil 1]
          states (->> exit-codes
                      (map (fn [e]
                             {:status 200
                              :body {:lifecycle-state "ACTIVE"
                                     :containers [{:id "test-container"
                                                   :exit-code e}]}}))
                      (ms/->source))
          ch (sut/poll-for-completion {:get-details (fn [_]
                                                      (ms/take! states))
                                       :poll-interval 100})
          res @(md/timeout! ch 1000 :timeout)]
      (is (not= :timeout res))
      (is (= 1 (-> res
                   :body
                   :containers
                   first
                   :exit-code)))))

  (testing "returns last response on completion"
    (let [r {:status 200
             :body {:lifecycle-state "FAILED"}}
          ch (sut/poll-for-completion {:get-details (fn [_]
                                                      (future r))})]
      (is (some? ch))
      (is (= r @(md/timeout! ch 200 :timeout))))))

(deftest get-full-instance-details
  (testing "returns full instance state"
    (let [[cid sid] (repeatedly random-uuid)
          exit-codes {cid 100
                      sid 200}
          containers [{:display-name "job"
                       :container-id cid}
                      {:display-name "sidecar"
                       :container-id sid}]]
      (with-redefs [ci/get-container-instance
                    (fn [& _]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:id "test-instance"
                         :lifecycle-state "INACTIVE"
                         :containers containers}}))
                    ci/get-container
                    (fn [_ {:keys [container-id]}]
                      (md/success-deferred
                       (if (contains? exit-codes container-id)
                         {:status 200
                          :body
                          {:id container-id
                           :exit-code (get exit-codes container-id)}}
                         {:status 400
                          :body
                          {:message "Invalid container id"}})))]
        (is (= {:status 200
                :body
                {:id "test-instance"
                 :lifecycle-state "INACTIVE"
                 :containers [{:display-name "job"
                               :container-id cid
                               :exit-code 100}
                              {:display-name "sidecar"
                               :container-id sid
                               :exit-code 200}]}}
               (deref (sut/get-full-instance-details ::test-client "test-instance")
                      200 :timeout)))))))

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
                    sut/poll-for-completion (fn [opts]
                                              (swap! calls conj opts)
                                              nil)]
        (is (some? (sut/run-instance {} {})))
        (is (not= :timeout (h/wait-until #(pos? (count @calls)) 200))))))

  (testing "when creation fails, does not call exit checker"
    (let [res {:status 400
               :body
               {:message "test error"}}]
      (with-redefs [ci/create-container-instance (constantly res)]
        (is (= res @(sut/run-instance {} {} {:exited? (md/error-deferred "should not be called")}))))))

  (testing "includes container logs if not inactive"
    (let [cid (random-uuid)
          containers [{:display-name "job"
                       :container-id cid}]]
      (with-redefs [ci/create-container-instance
                    (constantly
                     (md/success-deferred
                      {:status 200
                       :body
                       {:id "test-instance"
                        :containers containers}}))
                    ci/get-container-instance
                    (fn [& _]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:lifecycle-state "ACTIVE"
                         :containers containers}}))
                    ci/get-container
                    (fn [_ {:keys [container-id]}]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:id container-id
                         :exit-code 1}}))
                    ci/retrieve-logs
                    (constantly (md/success-deferred {:body "test container logs"}))]
        (is (= "test container logs"
               (-> (sut/run-instance {} {})
                   (deref)
                   :body
                   :containers
                   first
                   :logs))))))
  
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
                    sut/poll-for-completion
                    (fn [opts]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:id iid
                         :lifecycle-state "INACTIVE"
                         :containers containers}}))
                    ci/get-container
                    (fn [_ opts]
                      (md/success-deferred
                       {:status 200
                        :body
                        {:exit-code exit
                         :container-instance-id iid}}))
                    ci/retrieve-logs (constantly nil)
                    ci/delete-container-instance
                    (fn [_ opts]
                      (reset! deleted? true)
                      (md/success-deferred
                       {:status (if (= iid (:instance-id opts)) 200 400)}))]
        (is (map? (deref (sut/run-instance {} {} {:delete? true}) 200 :timeout)))
        (is (true? @deleted?)))))
  
  (testing "does not delete instance if configured but not created"
    (let [iid (random-uuid)
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
        (is (map? (deref (sut/run-instance {} {} {:delete? true}) 200 :timeout)))
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
