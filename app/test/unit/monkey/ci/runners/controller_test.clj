(ns monkey.ci.runners.controller-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [manifold.deferred :as md]
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
                 (assoc-in [:build :workspace] "/test/ws"))
          ;; Run controller async otherwise it will block tests
          res (md/future (sut/run-controller rt))]
      (is (not (md/realized? res)))
      
      (testing "creates run file"
        ;; Since we're running the controller async, wait until the run path exists,
        ;; which indicates it has started
        (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000))))
      
      (testing "performs git clone"
        (is (true? @cloned?)))
      
      (testing "restores build cache")

      (testing "stores workspace"
        (is (not-empty @(:stored (:workspace rt)))))
      
      (testing "waits until run file has been deleted"
        (is (= :timeout (deref res 100 :timeout)) "controller should be running until run file is deleted")
        (is (nil? (fs/delete run-path)))
        (is (not= :timeout (deref res 1000 :timeout))))

      (testing "saves build cache afterwards")

      (testing "creates abort file on error"))))
