(ns monkey.ci.runners.controller-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runners.controller :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(deftest run-controller
  (h/with-tmp-dir dir
    (let [run-path (str dir "/test.run")
          checkout-dir (str dir "/checkout")
          cloned? (atom nil)
          rt (-> (trt/test-runtime)
                 (assoc-in [:config :run-path] run-path)
                 (assoc-in [:build :git] {:url "git://test-url"
                                          :branch "main"})
                 (assoc-in [:build :script :script-dir] (str checkout-dir "/.monkeyci"))
                 (assoc-in [:git :clone] (fn [_] (reset! cloned? true)))
                 (assoc-in [:build :workspace] "/test/ws"))]
      (is (some? (sut/run-controller rt)))
      
      (testing "performs git clone"
        (is (true? @cloned?)))
      
      (testing "restores build cache")

      (testing "stores workspace"
        (is (not-empty @(:stored (:workspace rt)))))
      
      (testing "creates run file"
        (is (fs/exists? run-path)))
      
      (testing "waits until run file has been deleted"
        )

      (testing "saves build cache afterwards"))))
