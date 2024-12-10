(ns monkey.ci.runners.controller-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [manifold.deferred :as md]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runners.controller :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.test
             [blob :as tb]
             [runtime :as trt]]))

(defrecord FailingEventsPoster []
  p/EventPoster
  (post-events [this evt]
    (throw (ex-info "Always fails" {}))))

(deftest run-controller
  (h/with-tmp-dir dir
    (let [[run-path abort-path exit-path :as paths] (->> ["run" "abort" "exit"]
                                                         (map (partial str dir "/test.")))
          checkout-dir (str dir "/checkout")
          cloned? (atom nil)
          rt (-> (trt/test-runtime)
                 (update :config merge {:run-path run-path
                                        :abort-path abort-path
                                        :exit-path exit-path
                                        :m2-cache-path (str dir "/m2")})
                 (update :build merge {:customer-id "test-cust"
                                       :repo-id "test-repo"})
                 (assoc-in [:build :git] {:url "git://test-url"
                                          :branch "main"})
                 (assoc-in [:build :script :script-dir] (str checkout-dir "/.monkeyci"))
                 (assoc-in [:git :clone] (fn [_] (reset! cloned? true)))
                 (assoc-in [:build :workspace] "/test/ws"))
          exit-code 1232
          run! (fn [rt]
                 (doseq [p paths]
                   (when (fs/exists? p)
                     (fs/delete p)))
                 ;; Run controller async otherwise it will block tests
                 (md/future (sut/run-controller rt)))
          res (run! rt)]
      (is (nil? (spit exit-path (str exit-code))))
      (is (not (md/realized? res)))

      (testing "creates run file"
        ;; Since we're running the controller async, wait until the run path exists,
        ;; which indicates it has started
        (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000))))
      
      (testing "posts `build/start` event"
        (let [events (:events rt)]
          (is (some? events))
          (is (some? (->> (h/received-events events)
                          (h/first-event-by-type :build/start))))))
      
      (testing "posts `script/initializing` event"
        (let [events (:events rt)]
          (is (some? events))
          (is (some? (->> (h/received-events events)
                          (h/first-event-by-type :script/initializing))))))
      
      (testing "performs git clone"
        (is (true? @cloned?)))
      
      (testing "stores workspace"
        (is (not-empty @(:stored (:workspace rt)))))

      (testing "waits until run file has been deleted"
        (is (= :timeout (deref res 100 :timeout)) "controller should be running until run file is deleted")
        (is (nil? (fs/delete run-path)))
        (is (not= :timeout (deref res 1000 :timeout))))

      (testing "saves build cache afterwards"
        (is (not-empty (-> rt :build-cache :stored deref))))
      
      (testing "posts `build/end` event"
        (let [events (:events rt)]
          (is (some? events))
          (is (some? (->> (h/received-events events)
                          (h/first-event-by-type :build/end))))))

      (testing "returns exit code read from exit file"
        (is (= exit-code (deref res 100 :timeout))))

      (testing "on error"
        (testing "creates abort file"
          (is (not= 0
                    (-> rt
                        (assoc :events (->FailingEventsPoster)) ; force error
                        (run!)
                        (deref))))
          (is (fs/exists? abort-path)))

        (testing "posts build failure event"
          (h/reset-events (:events rt))
          (is (not= 0
                    (-> rt
                        (assoc :git (fn [& _] (throw (ex-info "test error" {})))) ; force error
                        (run!)
                        (deref))))
          (let [evt (->> (h/received-events (:events rt))
                         (h/first-event-by-type :build/end))]
            (is (some? evt))
            (is (= :error (:status evt))))))

      (testing "does not save build cache if hash has not changed"
        (let [cache-dir "/tmp/test-cache"
              bc (tb/test-store {"test-cust/test-repo.tgz" {:metadata {:app-hash (sut/app-hash)}}})
              rt (-> rt
                     (assoc :build-cache bc
                            :events (h/fake-events))
                     (assoc-in [:config :m2-cache-path] cache-dir))
              res (run! rt)]
          ;; Simulate running of the build
          (is (nil? (spit exit-path "0")))
          (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000)))
          (is (nil? (fs/delete run-path)))
          (is (not= :timeout (h/wait-until
                              (fn []
                                (some? (->> (h/received-events (:events rt))
                                            (h/first-event-by-type :build/end))))
                              1000)))
          (is (some? @res))
          ;; We expect only a restore action, no save
          (is (= [:restore]
                 (->> (tb/actions bc)
                      (map :action)))))))))

(deftest app-hash
  (testing "returns hash string for `deps.edn`"
    (is (= (u/file-hash "deps.edn")
           (sut/app-hash)))))
