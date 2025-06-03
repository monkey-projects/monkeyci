(ns monkey.ci.events.mailman-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.ci.events.mailman :as sut]
            [monkey.ci.test.mailman :as tm]
            [monkey.mailman.core :as mmc]))

(deftest make-component
  (testing "manifold"
    (let [c (-> {:type :manifold}
                (sut/make-component)
                (assoc :router (constantly "ok"))
                (co/start))]
      (testing "can make component"
        (is (some? c)))

      (testing "`start` registers listener"
        (is (some? (:listener c))))

      (testing "`stop` unregisters listener"
        (is (nil? (-> (co/stop c)
                      :listener))))))

  (testing "jms"
    (let [c (sut/make-component {:type :jms})]
      (testing "can make component"
        (is (some? c)))))

  (testing "nats"
    (let [c (sut/make-component {:type :nats})]
      (testing "can make component"
        (is (some? c))))))

(defrecord TestListener [unreg?]
  mmc/Listener
  (unregister-listener [this]
    (reset! unreg? true)))

(deftest routes
  (let [mailman (-> (sut/make-component {:type :manifold})
                    (co/start))
        api-config {:port 12342
                    :token "test-token"}]
    (testing "`start`"
      (let [c (-> (sut/->RouteComponent [] (constantly []) mailman {})
                  (co/start))]
        (testing "registers a listener"
          (is (some? (:listeners c))))))

    (testing "`stop` unregisters listener"
      (let [unreg? (atom false)]
        (is (nil? (-> (sut/map->RouteComponent {:listeners [(->TestListener unreg?)]})
                      (co/stop)
                      :listeners)))
        (is (true? @unreg?))))

    (testing "passes options to router"
      (let [c (-> (sut/map->RouteComponent {:make-routes (constantly [])
                                            :mailman (tm/test-component)
                                            :options {:destinations {::test-evt "test-dest"}}})
                  (co/start))]
        (is (= "test-dest" (-> (:listeners c)
                               first
                               :opts
                               :destinations
                               ::test-evt)))))))

(deftest merge-routes
  (testing "merges handlers together per type"
    (is (= [[::type-1 [::handler-1 ::handler-2]]
            [::type-2 [::handler-3 ::handler-4]]]
           (sut/merge-routes
            [[::type-1 [::handler-1]]
             [::type-2 [::handler-3]]]
            [[::type-1 [::handler-2]]]
            [[::type-2 [::handler-4]]])))))

(deftest listener-stream
  (let [broker (sut/make-component {:type :manifold})
        s (sut/listener-stream broker {})]
    
    (testing "returns source"
      (is (ms/source? s)))

    (testing "posted events are sent to stream"
      (let [evt {:type ::test-event}]
        (is (some? (sut/post-events broker [evt])))
        (is (= evt (deref (ms/take! s) 1000 :timeout)))))))
