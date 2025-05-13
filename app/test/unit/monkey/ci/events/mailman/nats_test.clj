(ns monkey.ci.events.mailman.nats-test
  (:require [monkey.nats.core :as nats]
            [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.nats :as sut]))

(deftest types-to-subjects
  (letfn [(verify-types [types]
            (let [f (sut/types-to-subjects "monkeyci.test")]
              (doseq [t types]
                (is (string? (f t))
                    (str "should map " t)))))]
    (testing "returns a subject for each event type"
      (testing "build types"
        (verify-types [:build/triggered
                       :build/pending
                       :build/queued
                       :build/initializing
                       :build/start
                       :build/end
                       :build/canceled
                       :build/updated]))

      (testing "script types"
        (verify-types [:script/initializing
                       :script/start
                       :script/end]))

      (testing "job types"
        (verify-types [:job/pending
                       :job/queued
                       :job/initializing
                       :job/start
                       :job/end
                       :job/skipped
                       :job/executed]))

      (testing "container types"
        (verify-types [:container/pending
                       :container/initializing
                       :container/job-queued])))))

(deftest nats-component
  (with-redefs [nats/make-connection (constantly ::nats)]
    (testing "`start`"
      (let [s (co/start (sut/map->NatsComponent {:config {:prefix "test"
                                                          :stream "test-stream"
                                                          :consumer "test-consumer"}}))]
        (testing "creates connection"
          (is (= ::nats (get-in s [:broker :nats])))
          (is (= ::nats (:conn s))))

        (testing "adds subjects according to prefix"
          (is (map? (:subjects s))))

        (testing "configures stream"
          (is (= "test-stream" (get-in s [:broker :config :stream]))))

        (testing "configures consumer"
          (is (= "test-consumer" (get-in s [:broker :config :consumer]))))))

    (testing "`stop`"
      (let [stopped? (atom false)
            closed? (atom false)
            make-closeable (fn [a]
                             (reify java.lang.AutoCloseable
                               (close [_]
                                 (reset! a true)
                                 nil)))
            broker (make-closeable stopped?)
            conn (make-closeable closed?)
            c (-> (sut/map->NatsComponent 
                   {:broker broker
                    :conn conn})
                  (co/stop))]
        (testing "stops broker"
          (is (true? @stopped?))
          (is (nil? (:broker c))))

        (testing "disconnects"
          (is (true? @closed?))
          (is (nil? (:conn c))))))

    (testing "`add-router`"
      (with-redefs [nats/subscribe (constantly ::test-sub)]
        (testing "registers listener per subject"
          (let [c (-> (sut/map->NatsComponent {:config {:prefix "test"}})
                      (co/start))
                l (em/add-router c [[::test []]] {})]
            (is (some? l))))))))
