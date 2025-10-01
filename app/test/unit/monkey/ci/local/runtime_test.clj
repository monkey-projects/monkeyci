(ns monkey.ci.local.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [com.stuartsierra.component :as co]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.local
             [config :as lc]
             [runtime :as sut]]
            [monkey.ci.protocols :as p]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]))

(deftest start-and-post
  (h/with-tmp-dir dir
    (let [broker (tm/test-component)
          evt {:type :test}
          r (-> {:mailman broker}
                (lc/set-work-dir dir)
                (lc/set-build (h/gen-build))
                (sut/start-and-post evt))]
      (testing "returns deferred"
        (is (md/deferred? r)))

      (testing "posts event to broker"
        (is (= [evt] (-> broker
                         :broker
                         (tm/get-posted))))))))

(deftest make-system
  (h/with-tmp-dir dir
    (let [sys (-> {}
                  (lc/set-work-dir dir)
                  (lc/set-build {:dek ::test-dek})
                  (sut/make-system))]
      (testing "has mailman"
        (is (some? (:mailman sys))))

      (testing "has artifacts"
        (is (p/blob-store? (:artifacts sys))))

      (testing "has cache"
        (is (p/blob-store? (:cache sys))))

      (testing "has build params"
        (is (satisfies? p/BuildParams (:params sys))))

      (testing "has api server"
        (is (some? (:api-server sys))))

      (testing "has mailman routes"
        (is (some? (:routes sys))))

      (testing "has print routes"
        (is (some? (:print-routes sys))))

      (testing "has state"
        (is (some? (:state sys))))

      (testing "has renderer"
        (is (some? (:renderer sys))))

      (testing "has podman routes"
        (is (some? (:podman sys))))

      (testing "has event stream"
        (is (some? (:event-stream sys))))

      (testing "has event pipe"
        (is (some? (:event-pipe sys))))

      (testing "when container build"
        (testing "has workspace"))

      (testing "has key decrypter"
        (let [kd (:key-decrypter sys)]
          (is (fn? kd))
          (is (= ::test-dek @(kd ::enc-key ::test-sid))))))

    (testing "after start"
      (let [sys (-> {:mailman (tm/test-component)}
                    (lc/set-work-dir dir)
                    (lc/set-build {:dek ::test-dek})
                    (sut/make-system)
                    (co/start))]
        (testing "podman routes"
          (let [p (:podman sys)]
            (is (some? p))
            (testing "is passed key decrypter"
              (is (some? (get p :key-decrypter))))))

        (is (some? (co/stop sys)))))))

(deftest event-pipe
  (let [broker (em/make-component {:type :manifold})
        stream (sut/new-event-stream)
        c (-> (sut/new-event-pipe)
              (assoc :mailman broker
                     :event-stream stream)
              (co/start))]
    (testing "`start`"
      (testing "registers listener in mailman"
        (is (some? (:listener c))))

      (testing "pipes all received events to stream"
        (let [evt {:type ::test-event}]
          (is (some? (em/post-events broker [evt])))
          (is (= evt (deref (ms/take! stream) 100 :timeout))))))

    (testing "`stop` unregisters listener"
      (let [s (co/stop c)]
        (is (nil? (:listener s)))
        (is (empty? (-> broker :broker (.listeners) deref)))))))

(deftest copy-store
  (h/with-tmp-dir dir
    (testing "copies from src to dest"
      (let [src (fs/create-dirs (fs/path dir "src"))
            dest (fs/path dir "dest")
            conf (lc/set-work-dir lc/empty-config src)
            ws (sut/copy-store conf)]
        (is (nil? (-> conf
                      (lc/get-workspace)
                      (fs/create-dirs)
                      (fs/path "test.txt")
                      (fs/file)
                      (spit "test file"))))
        (is (some? (p/restore-blob ws "test-src" dest)))
        (is (= "test file" (slurp (fs/file (fs/path dest "test.txt")))))))))

(deftest new-params
  (testing "fixed params when no api key configured"
    (is (instance? monkey.ci.params.FixedBuildParams
                   (sut/new-params {}))))

  (testing "combined params when api key configured"
    (is (instance? monkey.ci.params.MultiBuildParams
                   (-> {}
                       (lc/set-global-api {:url "http://test"
                                           :token "test-token"})
                       (sut/new-params))))))
