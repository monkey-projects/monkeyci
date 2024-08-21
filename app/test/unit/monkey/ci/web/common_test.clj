(ns monkey.ci.web.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.web.common :as sut]
            [monkey.ci.helpers :as h]))

(deftest run-build-async
  (testing "dispatches `build/pending` event"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e
              :runner (constantly :ok)}
          build {:build-id "test-build"
                 :sid ["test" "build"]}]
      (is (some? @(sut/run-build-async rt build)))
      (is (= 1 (count @recv)))
      (let [evt (first @recv)]
        (is (= :build/pending (:type evt)))
        (is (= build (:build evt)))
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
