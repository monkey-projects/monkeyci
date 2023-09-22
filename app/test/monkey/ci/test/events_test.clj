(ns monkey.ci.test.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :as ca]
            [monkey.ci.events :as sut]
            [monkey.ci.test.helpers :as h :refer [with-bus]]))

(defn read-or-timeout [c & [timeout]]
  (h/try-take c (or timeout 1000) :timeout))

(defn wait-until [p & [timeout]]
  (h/wait-until p (or timeout 1000)))

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
          (is (= evt (-> (first @received)
                         (select-keys (keys evt)))))))))

  (testing "bus is added as additional event key"
    (with-bus
      (fn [bus]
        (let [received (atom [])
              h (sut/register-handler bus :test (partial swap! received conj))
              evt {:type :test}]
          (is (true? (sut/post-event bus evt)))
          (is (not= :timeout (wait-until #(pos? (count @received)))))
          (is (= bus (-> (first @received)
                         :bus)))))))  

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

(defn register-test-handler
  "Registers a test handler on the bus that will receive all events
   of the given `type` and will filter them by `pred`.  Returns an
   atom that can be `deref`ed to check the received events."
  [bus type pred]
  (let [recv (atom [])]
    (sut/register-handler bus type
                          (fn [evt]
                            (when (pred evt)
                              (swap! recv conj evt))))
    recv))

(deftest register-pipeline
  (testing "passes events back to the bus after applying transducer"
    (with-bus
      (fn [bus]
        (let [tx (map #(assoc % :type :result))
              recv (register-test-handler bus :result identity)]
          (is (sut/handler? (sut/register-pipeline bus :input tx)))
          (is (true? (sut/post-event bus {:type :input})))
          (is (not= :timeout (h/wait-until #(pos? (count @recv)) 200)))))))

  (testing "adds bus as key to the event"
    (with-bus
      (fn [bus]
        (let [tx (map #(assoc % :type :result))
              recv (register-test-handler bus :result identity)]
          (is (sut/handler? (sut/register-pipeline bus :input tx)))
          (is (true? (sut/post-event bus {:type :input})))
          (is (not= :timeout (h/wait-until #(pos? (count @recv)) 200)))
          (is (= bus (-> @recv first :bus))))))))

(deftest unregister-handler
  (testing "removes sub from the bus, receives no events"
    (with-bus
      (fn [bus]
        (let [received (atom [])
              h (sut/register-handler bus :test (partial swap! received conj))
              evt {:type :test
                   :index 0}]
          (is (= bus (sut/unregister-handler bus h)))
          (is (true? (sut/post-event bus evt)))
          (is (= :timeout (h/wait-until #(pos? (count @received)) 200))))))))

(deftest wait-for
  (testing "returns a channel that holds the first match for the given transducer"
    (with-bus
      (fn [bus]
        (let [c (sut/wait-for bus :test-event (map identity))]
          (is (= :timeout (read-or-timeout c 100)) "channel should not hold a value initially")
          (is (true? (sut/post-event bus {:type :test-event})))
          (is (not= :timeout (read-or-timeout c))))))))

(defn ^{:event/handles ::test-event} test-handler [_]
  "nothing here")

(def ^{:event/tx ::test-event} test-tx 
  (map (constantly {:handled? true
                    :type :event/handled})))

(deftest register-ns-handlers
  (testing "registers all handlers with meta for the ns"
    (with-bus
      (fn [bus]
        (let [h (sut/register-ns-handlers bus (find-ns 'monkey.ci.test.events-test))]
          (is (every? sut/handler? h))
          (is (= 2 (count h))))))))
