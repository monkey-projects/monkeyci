(ns monkey.ci.web.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.spec.events :as se]
            [monkey.ci.web.common :as sut]
            [monkey.ci.helpers :as h]))

(deftest run-build-async
  (testing "dispatches `build/pending` event"
    (let [e (h/fake-events)
          rt {:events e
              :runner (constantly :ok)}
          build {:build-id "test-build"
                 :sid (h/gen-build-sid)}]
      (is (some? @(sut/run-build-async rt build)))
      (let [evt (->> (h/received-events e)
                     (h/first-event-by-type :build/pending))]
        (is (some? evt))
        (is (spec/valid? ::se/event evt))
        (is (= build (:build evt)))
        (is (= (:sid build) (:sid evt))))))

  (testing "dispatches `build/end` event when build fails to start"
    (let [e (h/fake-events)
          rt {:events e
              :runner (fn [_ _]
                        (throw (ex-info "test error" {})))}
          build {:build-id "test-build"
                 :sid (h/gen-build-sid)}]
      (is (some? @(sut/run-build-async rt build)))
      (let [evt (->> (h/received-events e)
                     (h/first-event-by-type :build/end))]
        (is (some? evt))
        (is (spec/valid? ::se/event evt))
        (is (some? (:build evt)))
        (is (= (:sid build) (:sid evt)))))))

(deftest parse-body
  (testing "parses string body according to content type"
    (is (= {:test-key "test value"}
           (-> {:body "{\"test_key\": \"test value\"}"
                :headers {"Content-Type" "application/json"}}
               (sut/parse-body)
               :body))))

  (testing "parses input stream body according to content type"
    (is (= {:test-key "test value"}
           (-> {:body (bs/to-input-stream (.getBytes "{\"test_key\": \"test value\"}"))
                :headers {"Content-Type" "application/json"}}
               (sut/parse-body)
               :body)))))
