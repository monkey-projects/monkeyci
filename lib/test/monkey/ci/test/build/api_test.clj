(ns monkey.ci.test.build.api-test
  (:require [clojure.test :refer [deftest testing is]]
            [martian
             [core :as martian]
             [test :as mt]]
            [monkey.ci.build.api :as sut]))

(def test-routes
  [{:route-name :get-params
    :method :get}])

(deftest build-params
  (letfn [(make-client [reply]
            (-> (martian/bootstrap "http://test" test-routes)
                (mt/respond-with {:get-params reply})))]
    
    (testing "invokes `get-params` endpoint on client"
      (let [m (make-client {:status 200
                            :body {"key" "value"}})
            ctx {:api {:client m}}]
        (is (= {"key" "value"} (sut/build-params ctx)))))

    (testing "throws exception on error"
      (let [m (make-client {:status 500
                            :body {:message "Test error"}})
            ctx {:api {:client m}}]
        (is (thrown? Exception (sut/build-params ctx)))))))
