(ns monkey.ci.runners.runtime-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.ci
             [metrics :as m]
             [prometheus :as prom]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runners.runtime :as sut]
            [monkey.ci.spec.runner :as sr]
            [monkey.ci.test
             [config :as tc]
             [mailman :as tm]]
            [monkey.mailman.core :as mmc]))

(def runner-config
  (assoc tc/base-config
         :checkout-base-dir "/base/dir"
         :build {:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id "test-build"
                 :sid ["test" "build"]}
         :api {:url "http://test"
               :token "test-token"}))

(deftest make-runner-system
  (let [sys (sut/make-runner-system runner-config)]
    (testing "creates system map"
      (is (map? sys)))

    (testing "contains runtime"
      (is (sut/runtime? (:runtime sys))))

    (testing "generates api token and random port"
      (let [conf (:api-config sys)]
        (is (string? (:token conf)))
        (is (int? (:port conf)))))))

(deftest with-runner-runtime
  (let [sys (sut/with-runner-system runner-config identity)
        rt (:runtime sys)]
    
    (testing "runtime matches spec"
      (is (spec/valid? ::sr/runtime rt)
          (spec/explain-str ::sr/runtime rt)))
    
    (testing "provides artifacts"
      (is (some? (:artifacts rt))))

    (testing "provides cache"
      (is (some? (:cache rt))))

    (testing "provides build cache"
      (is (some? (:build-cache rt))))

    (testing "provides global mailman"
      (is (some? (:global-mailman sys))))

    (testing "provides mailman routes"
      (is (some? (:routes sys))))

    (testing "provides local mailman"
      (is (some? (:mailman sys))))

    (testing "provides container routes"
      (is (some? (:container-routes sys))))

    (testing "passes api config to oci container routes"
      (let [sys (-> runner-config
                    (assoc :containers {:type :oci})
                    (sut/with-runner-system identity))]
        (is (= (get-in sys [:api-config :token])
               (-> sys :container-routes :api :token)))))

    (testing "provides event forwarder"
      (is (some? (:event-forwarder sys))))

    (testing "provides workspace"
      (is (some? (:workspace rt))))
 
    (testing "provides git"
      (is (fn? (get-in rt [:git :clone]))))

    (testing "adds config"
      (is (= runner-config (:config rt))))

    (testing "build"
      (let [build (:build rt)]
        (testing "sets workspace path"
          (is (string? (:workspace build))))

        (testing "calculates checkout dir"
          (is (= "/base/dir/test-build" (:checkout-dir build))))))

    (testing "starts api server at configured pod"
      (is (some? (get-in sys [:api-server :server])))
      (is (= (get-in sys [:api-server :port])
             (get-in sys [:api-config :port]))))

    (testing "system has metrics"
      (is (some? (:metrics sys))))

    (testing "has push gateway"
      (is (some? (:push-gw sys))))))

(deftest push-gw
  (let [conf {:push-gw
              {:host "test-host"
               :port 9091}}
        sys (-> (co/system-map
                 :push-gw (co/using (sut/new-push-gw conf) [:metrics])
                 :metrics (m/make-metrics))
                (co/start))
        p (:push-gw sys)
        pushed? (atom false)]
    (with-redefs [prom/push (fn [_] (reset! pushed? true))]
      (try
        (testing "creates push gw on start"
          (is (some? (:gw p))))
        (finally (co/stop sys))))

    (testing "pushes metrics on system stop"
      (is (true? @pushed?)))))

(defrecord TestListener [unreg?]
  mmc/Listener
  (unregister-listener [this]
    (reset! (:unreg? this) true)))

(deftest local-to-global-forwarder
  (testing "`start` registers listeners in broker"
    (let [broker (em/make-component {:type :manifold})
          r (-> (sut/new-local-to-global-forwarder)
                (assoc :mailman broker
                       :event-stream (ms/stream))
                (co/start))]
      (is (not-empty (:listeners r)))
      (is (every? (partial satisfies? mmc/Listener) (:listeners r)))
      (is (= (count (:listeners r)) (count @(.listeners (:broker broker)))))))

  (testing "`stop` unregisters listeners"
    (let [unreg? (atom false)]
      (is (nil? (-> (sut/new-local-to-global-forwarder)
                    (assoc :listeners [(->TestListener unreg?)])
                    (co/stop)
                    :listeners)))
      (is (true? @unreg?))))

  (let [local (->> (em/make-component {:type :manifold})
                   (co/start))
        global (tm/test-component)
        stream (ms/stream 1)
        fw (-> (sut/new-local-to-global-forwarder)
               (assoc :mailman local
                      :global-mailman global
                      :event-stream stream)
               (co/start))
        [acc :as evts] [{:type :script/initializing}
                        {:type ::other-event}]]
    (is (= evts @(em/post-events local evts)))

    (testing "forwards only accepted event types from local to global mailman"
      (is (= [acc] (tm/get-posted global))))

    (testing "forwards to event stream"
      (is (= evts (->> stream
                       (ms/stream->seq)
                       (take (count evts))))))

    (is (nil? (ms/close! stream)))))

(deftest global-to-local-routes
  (let [global (em/make-component {:type :manifold})
        local (tm/test-component)
        c (-> (sut/global-to-local-routes)
              (assoc :mailman global
                     :local-mailman local)
              (co/start))
        [cancel :as evts] [{:type :build/canceled
                            :sid ["test" "build"]}
                           [:type :build/end]]]
    (is (some? c))
    (is (= evts @(em/post-events global evts)))
    
    (testing "forwards only build/canceled to local mailman"
      (is (= [cancel] (tm/get-posted local))))))
