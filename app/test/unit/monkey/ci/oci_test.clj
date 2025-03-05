(ns monkey.ci.oci-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [java-time.api :as jt]
   [manifold.deferred :as md]
   [monkey.ci.config :as c]
   [monkey.ci.oci :as sut]
   [monkey.ci.time :as t]
   [monkey.oci.container-instance.core :as ci]
   [monkey.oci.os.stream :as os]
   [taoensso.telemere :as tt])
  (:import
   (java.io ByteArrayInputStream)))

(deftest stream-to-bucket
  (testing "pipes input stream to multipart"
    (with-redefs [os/input-stream->multipart (fn [ctx opts]
                                               {:context ctx
                                                :opts opts})]
      (let [in (ByteArrayInputStream. (.getBytes "test stream"))
            r (sut/stream-to-bucket {:key "value"} in)]
        (is (some? (:context r)))
        (is (= in (get-in r [:opts :input-stream])))))))

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

(deftest invocation-interceptor
  (testing "dispatches invocation event for given kind"
    (let [i (sut/invocation-interceptor ::test-module)
          s (tt/with-signal
              (is (= ::test-ctx ((:enter i) ::test-ctx))))]
      (is (= :event (:kind s)))
      (is (= :oci/invocation (:id s)))
      (is (= ::test-module (get-in s [:data :kind]))))))

(deftest delete-stale-instances
  (testing "lists and deletes active container instances that have timed out"
    (let [time (- (t/now) (* 2 c/max-script-timeout))
          deleted (atom [])]
      (with-redefs [ci/list-container-instances
                    (fn [_ opts]
                      (if (= "ACTIVE" (:lifecycle-state opts))
                        (md/success-deferred
                         {:status 200
                          :body
                          {:items
                           [{:id "test-instance"
                             :display-name "build-1"
                             :freeform-tags {:customer-id "test-cust"
                                             :repo-id "test-repo"}
                             :time-created (str (jt/instant time))}
                            {:id "other-instance"
                             :display-name "build-2"
                             :freeform-tags {:customer-id "test-cust"
                                             :repo-id "test-repo"}
                             :time-created (str (jt/instant (t/now)))}
                            {:id "non-build-instance"
                             :display-name "something else"
                             :time-created (str (jt/instant time))}]}})
                        (md/error-deferred (ex-info "Invalid options" opts))))

                    ci/delete-container-instance
                    (fn [_ opts]
                      (swap! deleted conj opts)
                      (if (= "test-instance" (:instance-id opts))
                        (md/success-deferred {:status 200})
                        (md/error-deferred
                         (ex-info "Wrong instance" opts))))]
        
        (is (= [{:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id "build-1"
                 :instance-id "test-instance"}]
               (sut/delete-stale-instances ::test-client "test-compartment")))
        (is (= [{:instance-id "test-instance"}]
               @deleted)))))

  (testing "fails on error response"
    (with-redefs [ci/list-container-instances (constantly
                                               (md/success-deferred
                                                {:status 500}))]
      (is (thrown? Exception (sut/delete-stale-instances ::test-client "test-compartment"))))))

(deftest credit-multiplier
  (testing "calculates value according to job settings"
    (is (= 4 (sut/credit-multiplier :arm 2 2))))

  (testing "calculates according to container instance settings"
    (is (= 4 (sut/credit-multiplier {:shape "CI.Standard.E4.Flex"
                                     :shape-config {:ocpus 1
                                                    :memory-in-g-bs 2}})))))

(deftest start-ci-interceptor
  (let [{:keys [enter] :as i} (sut/start-ci-interceptor ::test-client)
        inv (atom [])]
    (is (keyword? (:name i)))

    (with-redefs [ci/create-container-instance (fn [client ic]
                                                 (swap! inv conj {:client client
                                                                  :config ic})
                                                 (md/success-deferred {:status 200}))]
      (testing "`enter` starts container instance"
        (let [r (-> {}
                    (sut/set-ci-config ::test-config)
                    (enter))]
          (is (= {:container-instance ::test-config}
                 (-> @inv
                     first
                     :config)))
          (is (= ::test-client
                 (-> @inv
                     first
                     :client)))
          (is (= {:status 200}
                 (sut/get-ci-response r)))))

      (testing "fails if creation fails"))))

(deftest delete-ci-interceptor
  (let [{:keys [leave] :as i} (sut/delete-ci-interceptor ::test-client)]
    (is (keyword? (:name i)))
    
    (testing "deletes container instance with stored id"
      (let [ocid (random-uuid)
            ctx (sut/set-ci-id {} ocid)
            deleted (atom nil)]
        (with-redefs [ci/delete-container-instance (fn [client opts]
                                                     (reset! deleted opts)
                                                     (md/success-deferred {:status 200}))]
          (is (= ctx (leave ctx)))
          (is (= {:instance-id ocid} @deleted)))))))
