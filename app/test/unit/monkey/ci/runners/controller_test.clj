(ns monkey.ci.runners.controller-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.runners.controller :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(deftest run-controller
  (h/with-tmp-dir dir
    (let [run-path (str dir "/test.run")
          checkout-dir (str dir "/checkout")
          rt (-> (trt/test-runtime)
                 (assoc :run-path run-path
                        :git {:clone (constantly checkout-dir)})
                 (assoc-in [:build :git] {:url "git://test-url"
                                          :branch "main"})
                 (assoc-in [:build :script :script-dir] (str checkout-dir "/.monkeyci")))]
      (testing "performs git clone"
        (let [cloned? (atom nil)
              rt (assoc-in rt [:git :clone] (fn [_] (reset! cloned? true)))]
          (is (some? (sut/run-controller rt)))
          (is (true? @cloned?))))
      
      (testing "restores build cache")
      (testing "stores workspace")
      (testing "creates run file")
      (testing "starts api server")
      (testing "waits until run file has been deleted"))))
