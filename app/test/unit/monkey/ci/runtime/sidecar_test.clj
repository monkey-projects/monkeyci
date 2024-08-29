(ns monkey.ci.runtime.sidecar-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [monkey.ci.config.sidecar :as sc]
            [monkey.ci.runtime.sidecar :as sut]
            [monkey.ci.spec.sidecar :as ss]))

(def config
  (-> {}
      (sc/set-events {:type :manifold})))

(deftest make-system
  (testing "creates system map with runtime"
    (is (some? (:runtime (sut/make-system config))))))

(deftest with-runtime
  (testing "passes runtime component to arg"
    (let [res (sut/with-runtime config identity)]
      (is (sut/runtime? res))
      (is (spec/valid? ::ss/runtime res)))))
