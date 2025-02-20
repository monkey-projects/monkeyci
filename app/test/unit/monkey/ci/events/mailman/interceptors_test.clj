(ns monkey.ci.events.mailman.interceptors-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.mailman.interceptors :as sut]))

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

(deftest handle-build-error
  (let [{:keys [error] :as i} sut/handle-build-error
        test-error (ex-info "test error" {})]
    (is (keyword? (:name i)))
    (testing "has error handler"
      (is (fn? error)))

    (testing "returns `build/end` event with failure"
      (let [r (:result (error {} test-error))]
        (is (= :build/end (:type r)))
        (is (= "test error" (get-in r [:build :message])))))

    (testing "removes exception from context"
      (is (nil? (-> {:io.pedestal.interceptor.chain/error test-error}
                    (error test-error)
                    :io.pedestal.interceptor.chain/error))))))

