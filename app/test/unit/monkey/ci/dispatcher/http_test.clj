(ns monkey.ci.dispatcher.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.dispatcher.http :as sut]
            [ring.mock.request :as mock]))

(deftest make-handler
  (let [app (sut/make-handler {})]
    (testing "creates routing fn"
      (is (fn? app)))

    (testing "`/health` returns ok"
      (is (= 200 (-> (mock/request :get "/health")
                     (app)
                     :status))))

    (testing "`/metrics` returns metrics")))
