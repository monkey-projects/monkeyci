(ns monkey.ci.events.mailman.jms-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.jms :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.jms :as jms]
            [monkey.mailman.core :as mmc]))

(deftest topic-destinations
  (testing "applies configured prefix"
    (is (= "topic://monkeyci.test.builds"
           (-> {:prefix "monkeyci.test"}
               (sut/topic-destinations)
               (get :build/pending)))))

  (testing "maps known event types"
    (let [dests (sut/topic-destinations {:prefix "monkeyci.test"})]
      (doseq [t se/event-types] 
        (is (contains? dests t)
            (str "should map " t))))))

(deftest queue-destinations
  (testing "adds queue suffix to each destination"
    (is (= "topic://monkeyci.test.builds::monkeyci.test.builds.q"
           (-> {:prefix "monkeyci.test"}
               (sut/queue-destinations)
               (get :build/pending))))))

(deftest jms-component
  (with-redefs [jms/connect (constantly ::connected)
                jms/make-consumer (constantly ::consumer)
                jms/set-listener (constantly nil)]
    (testing "`start`"
      (let [s (-> (sut/map->JmsComponent {})
                  (co/start))]
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

            (testing "unregisters listeners")))))

    (testing "`add-router`"
      (testing "adds one listener per destination"
        (let [c (-> (sut/map->JmsComponent {})
                    (co/start))
              l (em/add-router c
                               [[:build/start [{:handler (constantly nil)}]]
                                [:build/end [{:handler (constantly nil)}]]]
                               {})]
          (is (= 1 (count l)))
          (is (every? (partial satisfies? mmc/Listener) l))))

      (testing "allows custom destinations"
        (let [c (-> (sut/map->JmsComponent {:config {:prefix "test"}})
                    (co/start))
              l (em/add-router c
                               [[:build/start [{:handler (constantly nil)}]]]
                               {:destinations {:build/start "test-dest"}})]
          (is (= 1 (count l)))
          (is (= "test-dest" (-> l first :destination))))))))
