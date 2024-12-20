(ns monkey.ci.web.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.spec.events :as se]
            [monkey.ci.storage :as st]
            [monkey.ci.web.common :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(deftest run-build-async
  (h/with-memory-store st
    (let [cust (h/gen-cust)]
      (is (some? (st/save-customer st cust)))
      (is (some? (st/save-customer-credit st {:customer-id (:id cust)
                                              :type :user
                                              :amount 1000})))
      
      (testing "dispatches `build/pending` event"
        (let [{e :events :as rt} (-> (trt/test-runtime)
                                     (trt/set-storage st)
                                     (trt/set-runner (constantly :ok)))
              build {:build-id "test-build"
                     :customer-id (:id cust)
                     :sid (h/gen-build-sid)}]
          (is (some? @(sut/run-build-async rt build)))
          (let [evt (->> (h/received-events e)
                         (h/first-event-by-type :build/pending))]
            (is (some? evt))
            (is (spec/valid? ::se/event evt))
            (is (= build (:build evt)))
            (is (= (:sid build) (:sid evt))))))

      (testing "dispatches `build/end` event when build fails to start"
        (let [{e :events :as rt} (-> (trt/test-runtime)
                                     (trt/set-storage st)
                                     (trt/set-runner (fn [_ _] 
                                                       (throw (ex-info "test error" {})))))
              build {:build-id "test-build"
                     :customer-id (:id cust)
                     :sid (h/gen-build-sid)}]
          (is (some? @(sut/run-build-async rt build)))
          (let [evt (->> (h/received-events e)
                         (h/first-event-by-type :build/end))]
            (is (some? evt))
            (is (spec/valid? ::se/event evt))
            (is (some? (:build evt)))
            (is (= (:sid build) (:sid evt)))))))

    (testing "fails when customer has no available credits"
      (let [cust (h/gen-cust)
            {e :events :as rt} (-> (trt/test-runtime)
                                   (trt/set-storage st))
            build {:build-id "test-build"
                   :customer-id (:id cust)
                   :sid (h/gen-build-sid)}]
        (is (some? (st/save-customer st cust)))
        (is (some? @(sut/run-build-async rt build)))
        (let [evt (->> (h/received-events e)
                       (h/first-event-by-type :build/end))]
          (is (some? evt))
          (is (spec/valid? ::se/event evt))
          (is (= :error (get-in evt [:build :status])))
          (is (= (:sid build) (:sid evt))))))))

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

(deftest req->ext-uri
  (testing "determines external uri using host, scheme and path"
    (is (= "http://test:1234/v1"
           (sut/req->ext-uri
            {:scheme :http
             :uri "/v1/customer/test-cust"
             :headers {"host" "test:1234"}}
            "/customer")))))
