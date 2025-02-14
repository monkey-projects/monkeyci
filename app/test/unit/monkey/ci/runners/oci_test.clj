(ns monkey.ci.runners.oci-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [edn :as edn]
             [oci :as oci]
             [protocols :as p]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.config.script :as cs]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runners.oci :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.oci.container-instance.core :as ci]
            [monkey.ci.helpers :as h]
            [monkey.mailman.core :as mmc]))

(defn- decode-vol-config [vol fn]
  (some->> vol
           :configs
           (filter (comp (partial = fn) :file-name))
           first
           :data
           (h/base64->)))

(deftest instance-config
  (let [build {:build-id "test-build"
               :git {:ssh-keys [{:private-key "test-privkey"
                                 :public-key "test-pubkey"}]}}
        ic (sut/instance-config {:log-config "test-log-config"
                                 :build-image-url "test-clojure-img"
                                 :private-key (h/generate-private-key)}
                                build)
        co (:containers ic)]
    (testing "creates container instance configuration"
      (is (map? ic))
      (is (= "test-build" (:display-name ic))))

    (testing "contains two containers"
      (is (= 2 (count co)))
      (is (every? string? (map :image-url co))))

    (testing "containers run as root"
      (is (every? zero? (map (comp :run-as-user :security-context) co))))

    (testing "assigns freeform tags containing build info")

    (testing "controller"
      (let [c (->> co
                   (filter (comp (partial = "controller") :display-name))
                   (first))]
        (is (some? c))

        (testing "runs monkeyci controller main"
          (let [args (:arguments c)]
            (is (= "controller" (last args)))))

        (testing "has config volume mount"
          (let [vm (oci/find-mount c "config")]
            (is (some? vm))
            (is (= "/home/monkeyci/config" (:mount-path vm)))))

        (testing "has checkout volume mount"
          (is (some? (oci/find-mount c oci/checkout-vol))))

        (testing "has ssh keys volume mount"
          (is (some? (oci/find-mount c "ssh-keys"))))))

    (testing "build"
      (let [c (->> co
                   (filter (comp (partial = "build") :display-name))
                   (first))
            env (:environment-variables c)]
        (is (some? c))

        (testing "uses configured image url"
          (is (= "test-clojure-img" (:image-url c))))

        (testing "invokes script"
          (is (= "bash" (first (:command c)))))

        (let [config-env (get env "CLJ_CONFIG")]
          (testing "sets `CLJ_CONFIG` location"
            (is (some? config-env)))

          (testing "mounts script to `CLJ_CONFIG`"
            (let [vm (oci/find-mount c "script")]
              (is (some? vm))
              (is (= config-env (:mount-path vm))))))

        (testing "sets work dir to script dir"
          (is (= (str oci/work-dir "/" (:build-id build) "/.monkeyci") (get env "MONKEYCI_WORK_DIR"))))

        (testing "sets run file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".run")
                 (get env "MONKEYCI_START_FILE"))))

        (testing "sets abort file"
          (is (= (str oci/checkout-dir "/" (:build-id build) ".abort")
                 (get env "MONKEYCI_ABORT_FILE"))))

        (testing "has checkout volume mount"
          (is (some? (oci/find-mount c oci/checkout-vol))))))

    (testing "volumes"
      (testing "contains config"
        (let [vol (oci/find-volume ic "config")]
          (is (some? vol))
          
          (testing "contains `config.edn`"
            (let [conf (some-> (decode-vol-config vol "config.edn")
                               (edn/edn->))]
              (is (some? conf)
                  "should contain config file")

              (testing "with git checkout dir"
                (is (= (str oci/work-dir "/" (:build-id build))
                       (get-in conf [:build :git :dir]))))

              (testing "with build checkout dir"
                (is (some? (get-in conf [:build :checkout-dir]))))
              
              (testing "with ssh keys dir"
                (is (some? (get-in conf [:build :git :ssh-keys-dir]))))

              (testing "with api token and port"
                (is (number? (get-in conf [:runner :api-port])))
                (is (string? (get-in conf [:runner :api-token]))))

              (testing "with public api token"
                (is (string? (get-in conf [:api :token]))))

              (testing "with `checkout-base-dir`"
                (is (= oci/work-dir (:checkout-base-dir conf))))))

          (testing "contains `logback.xml`"
            (let [f (decode-vol-config vol "logback.xml")]
              (is (= "test-log-config" f))))))

      (testing "contains script"
        (let [vol (oci/find-volume ic "script")]
          (is (some? vol))

          (let [deps (some-> (decode-vol-config vol "deps.edn")
                             (edn/edn->))]
            (testing "contains `deps.edn`"
              (is (some? deps)))

            (testing "provides monkeyci dependency"
              (is (string? (get-in deps [:aliases :monkeyci/build :extra-deps 'com.monkeyci/app :mvn/version]))))

            (let [sc (get-in deps [:aliases :monkeyci/build :exec-args :config])]
              (testing "passes script config as exec arg"
                (is (map? sc)))

              (testing "contains build"
                (let [r (cs/build sc)]
                  (is (= (:build-id build) (:build-id r)))
                  
                  (testing "without ssh keys"
                    (is (nil? (get-in r [:git :ssh-keys]))))

                  (testing "with credit multiplier"
                    (is (number? (:credit-multiplier r))))))

              (testing "contains api url and token"
                (let [api (cs/api sc)]
                  (is (string? (:url api)))
                  (is (string? (:token api))))))

            (testing "points to logback config file"
              (is (re-matches #"^-Dlogback\.configurationFile=.*$"
                              (-> (get-in deps [:aliases :monkeyci/build :jvm-opts])
                                  first))))

            (testing "sets m2 cache dir"
              (is (string? (:mvn/local-repo deps)))))

          (testing "contains `build.sh`"
            (is (some? (decode-vol-config vol "build.sh"))))))

      (testing "contains log config"
        (is (some? (oci/find-volume ic "log-config"))))

      (testing "contains ssh keys"
        (is (some? (oci/find-volume ic "ssh-keys")))))))

(deftest start-ci
  (let [{:keys [enter] :as i} (sut/start-ci ::test-client)
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

(deftest decrypt-ssh-keys
  (h/with-memory-store st
    (let [vault (v/->FixedKeyVault (v/generate-key))
          iv (v/generate-iv)
          {:keys [enter] :as i} (sut/decrypt-ssh-keys vault)]
      (is (keyword? (:name i)))

      (testing "decrypts key using customer iv"
        (let [ssh-key "decrypted-key"
              cust (h/gen-cust)
              build (-> (h/gen-build)
                        (assoc :customer-id (:id cust))
                        (assoc-in [:git :ssh-keys] [(p/encrypt vault iv ssh-key)]))
              _ (st/save-crypto st {:customer-id (:id cust)
                                    :iv iv})
              r (-> {:event {:type :build/pending
                             :sid (st/ext-build-sid build)
                             :build build}}
                    (em/set-db st)
                    (enter))]
          (is (= ["decrypted-key"] (-> r
                                       :event
                                       :build
                                       :git
                                       :ssh-keys))))))))

(deftest prepare-ci-config
  (let [{:keys [enter] :as i} (sut/prepare-ci-config {:private-key (h/generate-private-key)})]
    (is (keyword? (:name i)))
    
    (testing "updates ci config with container details"
      (let [r (enter {})]
        (is (= 2 (-> (sut/get-ci-config r)
                     :containers
                     count)))))))

(deftest save-runner-details
  (let [{:keys [enter]:as i} sut/save-runner-details]
    (is (keyword? (:name i)))
    
    (testing "`enter` saves ci results in db"
      (h/with-memory-store st
        (let [sid (repeatedly 3 cuid/random-cuid)
              ctx (-> {:event {:sid sid}}
                      (sut/set-ci-response {:status 200
                                            :body {:id "test-ocid"}})
                      (em/set-db st))
              r (enter ctx)]
          (is (= ctx r))
          (is (= {:runner :oci
                  :details {:instance-id "test-ocid"}}
                 (st/find-runner-details st sid))))))))

(deftest load-runner-details
  (let [{:keys [enter]:as i} sut/load-runner-details]
    (is (keyword? (:name i)))
    
    (testing "`enter` fetches runner details from db"
      (h/with-memory-store st
        (let [sid (repeatedly 3 cuid/random-cuid)
              ctx (-> {:event {:sid sid}}
                      (em/set-db st))
              details {:runner :oci
                       :details {:instance-id (random-uuid)}}
              _ (st/save-runner-details st sid details)
              r (enter ctx)]
          (is (= details
                 (sut/get-runner-details r))))))))

(deftest handle-error
  (let [{:keys [error] :as i} sut/handle-error
        test-error (ex-info "test error" {})]
    (is (keyword? (:name i)))
    (testing "has error handler"
      (is (fn? error)))

    (testing "returns `build/end` event with failure"
      (let [r (:result (error {} test-error))]
        (is (= :build/end (:type r)))
        (is (= "test error" (get-in r [:build :message])))))

    (testing "removes exception from context"
      (is (nil? (-> {:io.pedestal.interceptor.chain/error test-error}
                    (error test-error)
                    :io.pedestal.interceptor.chain/error))))))

(deftest initialize-build
  (testing "returns `build/initializing` event"
    (is (= :build/initializing
           (-> {:event {:build {:sid ::test-build}}}
               (sut/initialize-build)
               :type)))))

(deftest delete-instance
  (testing "deletes container instance according to runner details"
    (h/with-memory-store st
      (let [build (h/gen-build)
            sid (st/ext-build-sid build)
            ocid (random-uuid)
            ctx (-> {:event {:sid sid
                             :type :build/end
                             :build build}}
                    (em/set-db st)
                    (sut/set-runner-details {:details {:instance-id ocid}}))
            deleted (atom nil)]
        (with-redefs [ci/delete-container-instance (fn [client opts]
                                                     (reset! deleted opts)
                                                     (md/success-deferred {:status 200}))]
          (is (nil? (sut/delete-instance ::test-client ctx)))
          (is (= {:instance-id ocid} @deleted)))))))

(deftest make-router
  (let [build (h/gen-build)
        st (st/make-memory-storage)
        conf {:private-key (h/generate-private-key)}]
    
    (testing "`build/pending`"
      (testing "returns `build/initializing` event"
        (let [fake-start-ci {:name ::sut/start-ci
                             :enter (fn [ctx]
                                      (sut/set-ci-response ctx {:status 200
                                                                :body {:id "test-instance"}}))}
              router (-> (sut/make-router conf st (h/fake-vault))
                         (mmc/replace-interceptors [fake-start-ci]))

              r (router {:type :build/pending
                         :sid (st/ext-build-sid build)
                         :build build})
              res (-> r
                      first
                      :result
                      first)]
          (is (spec/valid? ::se/event res))
          (is (= :build/initializing (:type res)))))

      (testing "when instance creation fails, returns `build/end` event"
        (let [fail-start-ci {:name ::sut/start-ci
                             :enter (fn [ctx]
                                      (sut/set-ci-response ctx {:status 500
                                                                :body {:id "test-instance"}}))}
              router (-> (sut/make-router conf st (h/fake-vault))
                         (mmc/replace-interceptors [fail-start-ci]))

              r (router {:type :build/pending
                         :sid (st/ext-build-sid build)
                         :build build})
              res (-> r
                      first
                      :result
                      first)]
          (is (spec/valid? ::se/event res))
          (is (= :build/end (:type res)))
          (is (= :error (-> res :build :status))))))))

(defrecord FakeListener [unreg?]
  mmc/Listener
  (unregister-listener [this]
    (reset! unreg? true)))

(deftest runner-component
  (let [mm (-> (em/make-component {:type :manifold})
               (co/start))]
    (testing "`start` registers broker listeners"
      (is (not-empty (-> (sut/map->OciRunner {:mailman mm})
                         (co/start)
                         :listeners))))

    (testing "`stop` unregisters broker listeners"
      (let [unreg? (atom false)
            l (->FakeListener unreg?)]
        (is (nil? (-> (sut/map->OciRunner {:listeners [l]})
                      (co/stop)
                      :listeners)))
        (is (true? @unreg?))))))
