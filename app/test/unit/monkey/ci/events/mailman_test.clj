(ns monkey.ci.events.mailman-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman :as sut]
            [monkey.ci.test.mailman :as tm]
            [monkey.jms :as jms]
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
    (with-redefs [jms/connect (constantly ::connected)
                  jms/make-consumer (constantly ::consumer)
                  jms/set-listener (constantly nil)]
      (let [c (sut/make-component {:type :jms})]
        (testing "can make component"
          (is (some? c)))

        (testing "`start`"
          (let [s (co/start c)]
            (testing "connects to broker"
              (is (= ::connected (get-in s [:broker :context]))))))

        (testing "`stop`"
          (let [closed? (atom false)
                disconnected? (atom false)
                broker (reify java.lang.AutoCloseable
                         (close [_]
                           (reset! closed? true)))]
            (with-redefs [jms/disconnect (fn [_]
                                           (reset! disconnected? true))]
              (let [s (-> (sut/map->JmsComponent {:broker broker})
                          (co/stop))]
                (testing "disconnects from broker"
                  (is (nil? (:broker s)))
                  (is (true? @disconnected?)))

                (testing "closes broker"
                  (is (true? @closed?)))

                (testing "unregisters listeners"))))))

      (testing "`add-router`"
        (testing "adds one listener per destination"
          (let [c (-> (sut/make-component {:type :jms})
                      (co/start))
                l (sut/add-router c
                                  [[:build/start [{:handler (constantly nil)}]]
                                   [:build/end [{:handler (constantly nil)}]]]
                                  {})]
            (is (= 1 (count l)))
            (is (every? (partial satisfies? mmc/Listener) l))))

        (testing "allows custom destinations"
          (let [c (-> (sut/make-component {:type :jms
                                           :prefix "test"})
                      (co/start))
                l (sut/add-router c
                                  [[:build/start [{:handler (constantly nil)}]]]
                                  {:destinations {:build/start "test-dest"}})]
            (is (= 1 (count l)))
            (is (= "test-dest" (-> l first :destination)))))))))

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
      (let [c (-> (sut/->RouteComponent [] (constantly []) mailman)
                  (co/start))]
        (testing "registers a listener"
          (is (some? (:listeners c))))))

    (testing "`stop` unregisters listener"
      (let [unreg? (atom false)]
        (is (nil? (-> (sut/map->RouteComponent {:listeners [(->TestListener unreg?)]})
                      (co/stop)
                      :listeners)))
        (is (true? @unreg?))))

    (testing "passes destinations to router"
      (let [c (-> (sut/map->RouteComponent {:make-routes (constantly [])
                                            :mailman (tm/test-component)
                                            :destinations {::test-evt "test-dest"}})
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
