(ns monkey.ci.test.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [monkey.ci.events :as sut]))

(defn read-or-timeout [c & [timeout]]
  (let [[v p] (ca/alts!! [c (ca/timeout (or timeout 1000))])]
    (if (= c p) v :timeout)))

(defn wait-until [p & [timeout]]
  (let [timeout (or timeout 1000)
        start (System/currentTimeMillis)]
    (loop [v (p)]
      (if v
        v
        (if (> (System/currentTimeMillis) (+ start timeout))
          :timeout
          (do
            (Thread/sleep 100)
            (recur (p))))))))

(deftest make-bus
  (testing "creates pub"
    (is (sut/bus? (sut/make-bus)))))

(deftest default-channel
  (testing "created using `make-channel`"
    (is (some? (sut/make-channel))))

  (testing "passes events"
    (let [c (sut/make-channel)
          evt {:type :test-event}]
      (is (true? (ca/>!! c evt)))
      (is (= evt (read-or-timeout c)))
      (is (nil? (ca/close! c))))))

(defn with-bus [f]
  (let [bus (sut/make-bus)]
    (try
      (f bus)
      (finally
        (sut/close-bus bus)))))

(deftest register-handler
  (testing "registers a handler for given type"
    (with-bus
      (fn [bus]
        (let [h (sut/register-handler bus :test (constantly :handled))]
          (is (sut/handler? h))))))

  (testing "handler receives each posted event of that type"
    (with-bus
      (fn [bus]
        (let [received (atom [])
              h (sut/register-handler bus :test (partial swap! received conj))
              evt {:type :test
                   :index 0}]
          (is (true? (sut/post-event bus evt)))
          (is (not= :timeout (wait-until #(pos? (count @received)))))
          (is (= 1 (count @received)))
          (is (= evt (first @received)))))))

  (testing "handler exceptions are sent as error events to the bus"
    (with-bus
      (fn [bus]
        (let [errors (atom [])]
          (is (some? (sut/register-handler
                      bus :error
                      (partial swap! errors conj))))
          (is (some? (sut/register-handler
                      bus :failing
                      (fn [evt]
                        (throw (ex-info "test error" {:event evt}))))))
          (is (true? (sut/post-event bus {:type :failing})))
          (is (not= :timeout (wait-until #(pos? (count @errors)))))
          (is (= 1 (count @errors)))
          (is (= :error (-> @errors (first) :type))))))))

(deftest wait-for
  (testing "returns a channel that holds the first match for the given transducer"
    (with-bus
      (fn [bus]
        (let [c (sut/wait-for bus :test-event (map identity))]
          (is (= :timeout (read-or-timeout c 100)) "channel should not hold a value initially")
          (is (true? (sut/post-event bus {:type :test-event})))
          (is (not= :timeout (read-or-timeout c))))))))
