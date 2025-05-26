(ns monkey.ci.runtime.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci.runtime.common :as sut]
            [monkey.ci.utils :as u]))

(defrecord TestComponent [started? stopped?]
  co/Lifecycle
  (start [this]
    (assoc this :started (reset! started? true)))

  (stop [this]
    (assoc this :stopped (reset! stopped? true))))

(deftest with-system-async
  (let [started? (atom false)
        stopped? (atom false)
        sys (->TestComponent started? stopped?)
        d (md/deferred)
        hook-registered? (atom false)]
    (with-redefs [u/add-shutdown-hook! (fn [h]
                                         (reset! hook-registered? true))]
      (let [r (sut/with-system-async sys (constantly d))]
        (testing "starts system and invokes `f`"
          (is (true? @started?))
          (is (md/deferred? r)))
        
        (testing "stops system on realization"
          (is (some? (md/success! d ::ok)))
          (is (true? @stopped?))
          (is (md/realized? r)))

        (testing "registers shutdown hook"
          (is (true? @hook-registered?)))))))
