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

(deftest ctx->oci-config
  (testing "gets key from context"
    (let [c {:key "value"}]
      (is (= c (sut/ctx->oci-config {:config c} :config)))))

  (testing "merges general oci config"
    (is (= {:region "test-region"
            :key "value"}
           (sut/ctx->oci-config {:oci
                                 {:region "test-region"}
                                 :config
                                 {:key "value"}}
                                :config)))))

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
        (is (= exit (deref (sut/run-instance {} {}) 200 :timeout)))))))
