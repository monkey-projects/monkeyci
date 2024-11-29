(ns monkey.ci.runners.controller-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [manifold.deferred :as md]
            [monkey.ci.protocols :as p]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runners.controller :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(defrecord FailingEventsPoster []
  p/EventPoster
  (post-events [this evt]
    (throw (ex-info "Always fails" {}))))

(deftest run-controller
  (h/with-tmp-dir dir
    (let [[run-path abort-path exit-path] (->> ["run" "abort" "exit"]
                                               (map (partial str dir "/test.")))
          checkout-dir (str dir "/checkout")
          cloned? (atom nil)
          rt (-> (trt/test-runtime)
                 (update :config merge {:run-path run-path
                                        :abort-path abort-path
                                        :exit-path exit-path})
                 (assoc-in [:build :git] {:url "git://test-url"
                                          :branch "main"})
                 (assoc-in [:build :script :script-dir] (str checkout-dir "/.monkeyci"))
                 (assoc-in [:git :clone] (fn [_] (reset! cloned? true)))
                 (assoc-in [:build :workspace] "/test/ws"))
          exit-code 1232
          ;; Run controller async otherwise it will block tests
          res (md/future (sut/run-controller rt))]
      (is (nil? (spit exit-path (str exit-code))))
      (is (not (md/realized? res)))

      (testing "creates run file"
        ;; Since we're running the controller async, wait until the run path exists,
        ;; which indicates it has started
        (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000))))
      
      (testing "posts `script/initializing` event"
        (let [events (:events rt)]
          (is (some? events))
          (is (some? (->> (h/received-events events)
                          (h/first-event-by-type :script/initializing))))))
      
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

      (testing "posts `build/end` event"
        (let [events (:events rt)]
          (is (some? events))
          (is (some? (->> (h/received-events events)
                          (h/first-event-by-type :build/end))))))

      (testing "returns exit code read from exit file"
        (is (= exit-code (deref res 100 :timeout))))
      
      (testing "creates abort file on error"
        (is (not= 0
                  (-> rt
                      (assoc :events (->FailingEventsPoster)) ; force error
                      (sut/run-controller))))
        (is (fs/exists? abort-path))))))
