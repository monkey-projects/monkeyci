(ns monkey.ci.events.mailman.interceptors-test
  (:require [babashka.process :as bp]
            [clojure.test :refer [deftest is testing]]
            [manifold
             [bus :as mb]
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.events.mailman.interceptors :as sut]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]))

(deftest add-time
  (let [{:keys [leave] :as i} sut/add-time]
    (is (keyword? (:name i)))
    
    (testing "sets event times"
      (is (number? (-> {:result [{:type ::test-event}]}
                       (leave)
                       :result
                       first
                       :time))))))

(deftest trace-evt
  (let [{:keys [enter leave] :as i} sut/trace-evt]
    (is (keyword? (:name i)))
    
    (testing "`enter` returns context as-is"
      (is (= ::test-ctx (enter ::test-ctx))))

    (testing "`leave` returns context as-is"
      (is (= ::test-ctx (leave ::test-ctx))))))

(deftest with-state
  (let [state (atom {:key :initial})
        {:keys [enter leave] :as i} (sut/with-state state)]
    (is (keyword? (:name i)))

    (testing "`enter` adds state to context"
      (is (= @state (-> (enter {})
                        (sut/get-state)))))

    (testing "`leave` updates state"
      (is (some? (-> (-> {}
                         (sut/set-state {:key :updated})
                         (leave)))))
      (is (= {:key :updated}
             @state)))))

(deftest no-result
  (let [{:keys [leave] :as i} sut/no-result]
    (is (keyword? (:name i)))
    
    (testing "`leave` removes result from context"
      (is (nil? (-> {:result ::test-result}
                    (leave)
                    :result))))))

(deftest handle-build-error
  (let [{:keys [error] :as i} sut/handle-build-error
        test-error (ex-info "test error" {})]
    (is (keyword? (:name i)))
    (testing "has error handler"
      (is (fn? error)))

    (testing "returns `build/end` event with failure"
      (let [r (:result (error {} test-error))]
        (is (= :build/end (:type r)))
        (is (= "test error" (get-in r [:build :message])))))))

(deftest handle-job-error
  (let [{:keys [error] :as i} sut/handle-job-error]
    (is (keyword? (:name i)))
    
    (testing "`error` puts `job/end` failure event in result"
      (let [r (-> (error {:event
                          {:type :job/initializing
                           :sid ["build" "sid"]
                           :job-id "test-job"}}
                         (ex-info "test error" {}))
                  (sut/get-result))]
        (is (= [:job/end]
               (map :type r)))
        (is (= "test-job" (-> r first :job-id)))
        (is (= :failure (-> r first :status)))))))

(deftest update-bus
  (testing "`enter` publishes event to update bus"
    (let [bus (mb/event-bus)
          s (mb/subscribe bus :build/updated)
          {:keys [enter] :as i} (sut/update-bus bus)
          evt {:type :build/updated
               :build (h/gen-build)}]
      (is (keyword? (:name i)))
      (is (some? (enter {:event evt})))
      (is (= evt (deref (ms/take! s) 1000 :timeout))))))


(deftest realize-deferred
  (let [e (md/deferred)
        {:keys [leave] :as i} (sut/realize-deferred e)]
    (is (keyword? (:name i)))
    
    (testing "`leave` sets result in deferred"
      (is (map? (-> {:result ::test-result}
                    (leave))))
      (is (= ::test-result (deref e 100 :timeout))))))

(deftest start-process
  (let [{:keys [leave] :as i} sut/start-process]
    (is (keyword? (:name i)))

    (testing "`leave` starts child process"
      (with-redefs [bp/process identity]
        (is (= ::test-cmd (-> {:result ::test-cmd}
                              (leave)
                              (sut/get-process))))))))

(deftest forwarder
  (let [dest (tm/test-component)
        {:keys [enter] :as i} (sut/forwarder ::test dest)]
    (is (= ::test (:name i)))

    (testing "`enter` forwards received event to destination broker"
      (let [evt {:type ::test-event}
            ctx {:event evt}]
        (is (= ctx (enter ctx)))
        (is (= [evt] (tm/get-posted dest)))))))

(deftest use-db
  (testing "adds db to context"
    (let [i (sut/use-db ::test-db)]
      (is (= ::sut/use-db (:name i)))
      (is (fn? (:enter i)))
      (is (= ::test-db (-> ((:enter i) {})
                           ::sut/db))))))

