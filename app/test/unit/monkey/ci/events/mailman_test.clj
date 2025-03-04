(ns monkey.ci.events.mailman-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman :as sut]
            [monkey.ci.helpers :as h]
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
      (let [c (-> (sut/make-component {:type :jms})
                  (assoc :routes {:routes [[:build/start [{:handler (constantly nil)}]]]}))]
        (testing "can make component"
          (is (some? c)))

        (testing "`start`"
          (let [s (co/start c)]
            (testing "connects to broker"
              (is (= ::connected (get-in s [:broker :context]))))

            (testing "registers listeners"
              (is (not-empty (:listeners s))))

            (testing "does not registers compatibility bridge if not configured"
              (is (nil? (:bridge s))))))

        (testing "`stop`"
          (let [closed? (atom false)]
            (with-redefs [jms/disconnect (fn [_]
                                           (reset! closed? true))]
              (let [s (-> (sut/map->JmsComponent {:broker ::test-broker})
                          (co/stop))]
                (testing "disconnects from broker"
                  (is (nil? (:broker s)))
                  (is (true? @closed?)))

                (testing "unregisters listeners"))))))

      (testing "with compatibility bridge"
        (let [c (->  {:type :jms
                      :bridge {:dest "queue://legacy-dest"}}
                     (sut/make-component)
                     (co/start))]
          (testing "registers listener at start"
            (is (some? (:bridge c))))))

      (testing "`add-router` adds one listener per destination"
        (let [c (-> (sut/make-component {:type :jms})
                    (co/start))
              l (sut/add-router c
                                [[:build/start [{:handler (constantly nil)}]]
                                 [:build/end [{:handler (constantly nil)}]]]
                                {})]
          (is (= 1 (count l)))
          (is (every? (partial satisfies? mmc/Listener) l)))))))

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
      (let [c (-> (sut/->RouteComponent [] (constantly [] )mailman)
                  (co/start))]
        (testing "registers a listener"
          (is (some? (:listener c))))))

    (testing "`stop` unregisters listener"
      (let [unreg? (atom false)]
        (is (nil? (-> (sut/map->RouteComponent {:listener (->TestListener unreg?)})
                      (co/stop)
                      :listener)))
        (is (true? @unreg?))))))

(deftest merge-routes
  (testing "merges handlers together per type"
    (is (= [[::type-1 [::handler-1 ::handler-2]]
            [::type-2 [::handler-3 ::handler-4]]]
           (sut/merge-routes
            [[::type-1 [::handler-1]]
             [::type-2 [::handler-3]]]
            [[::type-1 [::handler-2]]]
            [[::type-2 [::handler-4]]])))))
