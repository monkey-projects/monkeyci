(ns monkey.ci.dispatcher.runtime-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.dispatcher.runtime :as sut]))

(deftest make-system
  (let [sys (sut/make-system {})]
    (testing "provides http server"
      (is (some? (:http-server sys))))

    (testing "provides http app"
      (is (some? (:http-app sys))))

    (testing "provides metrics"
      (is (some? (:metrics sys))))))

(deftest http-app
  (testing "`start` creates handler fn"
    (is (fn? (-> (sut/map->HttpApp {})
                 (co/start)
                 :handler)))))
