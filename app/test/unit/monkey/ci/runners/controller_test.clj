(ns monkey.ci.runners.controller-test
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.test :refer [deftest is testing]]
            [manifold.deferred :as md]
            [monkey.ci.runners.controller :as sut]
            [monkey.ci.test
             [blob :as tb]
             [helpers :as h]
             [mailman :as tm]
             [runtime :as trt]]
            [monkey.ci.utils :as u]
            [monkey.mailman.core :as mmc]))

(deftest download-src
  (testing "no-op if the source is local"
    (let [build {}]
      (is (= build (sut/download-src build {})))))

  (testing "gets src using git fn"
    (is (= "test/dir" (-> {:git {:url "http://git.test"}
                           :build-id "test-build"}
                          (sut/download-src {:git {:clone (constantly "test/dir")}
                                             :config {:checkout-base-dir "/tmp"}})
                          :checkout-dir))))

  (testing "passes git config to git fn"
    (let [git-config {:url "http://test"
                      :branch "main"
                      :id "test-id"}]
      (is (= "ok" (-> {:git git-config
                       :build-id "test-build"}
                      (sut/download-src
                       {:git {:clone (fn [c]
                                       (if (= (select-keys c (keys git-config)) git-config)
                                         "ok"
                                         (str "failed: " (pr-str c))))}})
                      :checkout-dir)))))

  (testing "calculates checkout dir if not specified"
    (is (cs/ends-with? (-> {:git {:url "http:/test.git"}
                                    :build-id "test-build"}
                           (sut/download-src
                            {:config {:checkout-base-dir "test-work-dir"}
                             :git {:clone :dir}})
                           :checkout-dir)
                       "test-work-dir/test-build")))

  (testing "uses dir from build config if specified"
    (is (= "git-dir"
           (-> {:git {:url "http:/test.git"
                      :dir "git-dir"}}
               (sut/download-src {:args {:workdir "test-work-dir"}
                                  :git {:clone :dir}})
               :checkout-dir))))

  (testing "calculates script dir"
    (is (re-matches #".*test/dir/test-script$"
                    (-> {:git {:url "http://git.test"}
                         :script {:script-dir "test-script"}
                         :build-id "test-build"}
                        (sut/download-src {:git {:clone (constantly "test/dir")}
                                           :config {:checkout-base-dir "/tmp"}})
                        :script
                        :script-dir))))

  (testing "uses default script dir when none specified"
    (is (re-matches #".*test/dir/.monkeyci$"
                    (-> {:git {:url "http://git.test"}
                         :build-id "test-build"}
                        (sut/download-src
                         {:git {:clone (constantly "test/dir")}
                          :config {:checkout-base-dir "checkout"}})
                        :script
                        :script-dir)))))

(deftest store-src
  (testing "does nothing if no workspace configured"
    (let [build {}]
      (is (= build (sut/store-src build {})))))

  (testing "stores src dir using blob and build id with extension"
    (let [stored (atom {})
          rt {:workspace (h/fake-blob-store stored)}
          build {:checkout-dir "test-checkout"
                 :sid ["test-cust" "test-repo" "test-build"]}]
      (is (some? (sut/store-src build rt)))
      (is (= {"test-cust/test-repo/test-build.tgz" "test-checkout"} @stored))))

  (testing "returns updated build"
    (let [rt {:workspace (h/fake-blob-store (atom {}))}
          build {:checkout-dir "test-checkout"
                 :sid ["test-build"]}]
      (is (= (assoc build :workspace "test-build.tgz")
             (sut/store-src build rt))))))

(defrecord FailingEventsPoster []
  mmc/EventPoster
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
                 (update :build merge {:org-id "test-cust"
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
          res (run! rt)
          first-evt-by-type (fn [t]
                              (->> (:mailman rt)
                                   (tm/get-posted)
                                   (h/first-event-by-type t)))]
      (is (nil? (spit exit-path (str exit-code))))
      (is (not (md/realized? res)))

      (testing "creates run file"
        ;; Since we're running the controller async, wait until the run path exists,
        ;; which indicates it has started
        (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000))))
      
      (testing "posts `build/start` event"
        (is (some? (first-evt-by-type :build/start))))
      
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
        (is (some? (first-evt-by-type :build/end))))

      (testing "returns exit code read from exit file"
        (is (= exit-code (deref res 100 :timeout))))

      (testing "on error"
        (testing "creates abort file"
          (is (not= 0
                    (-> rt
                        (assoc :mailman {:broker (->FailingEventsPoster)}) ; force error
                        (run!)
                        (deref 1000 :timeout))))
          (is (fs/exists? abort-path)))

        (testing "posts build failure event"
          (tm/clear-posted! (:mailman rt))
          (is (not= 0
                    (-> rt
                        (assoc :git {:clone (fn [& _] (throw (ex-info "test error" {})))}) ; force error
                        (run!)
                        (deref))))
          (let [evt (first-evt-by-type :build/end)]
            (is (some? evt))
            (is (= :error (:status evt)))
            (is (= "test error" (:message evt))))))

      (testing "does not save build cache if hash has not changed"
        (let [cache-dir "/tmp/test-cache"
              bc (tb/test-store {"test-cust/test-repo.tgz" {:metadata {:app-hash (sut/app-hash)}}})
              rt (-> rt
                     (assoc :build-cache bc)
                     (assoc-in [:config :m2-cache-path] cache-dir))
              res (run! rt)]
          ;; Simulate running of the build
          (is (nil? (spit exit-path "0")))
          (is (not= :timeout (h/wait-until #(fs/exists? run-path) 1000)))
          (is (nil? (fs/delete run-path)))
          (is (not= :timeout (h/wait-until
                              #(some? (first-evt-by-type :build/end))
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

(deftest script-exit
  (testing "puts the event in the configured deferred"
    (let [r (md/deferred)
          evt {:type :script/exit
               :status :success}]
      (is (nil? (sut/script-exit r {:event evt})))
      (is (= evt (deref r 100 :timeout))))))

(deftest make-routes
  (testing "`:script/end` event"
    (testing "is handled"
      (is (contains? (->> (sut/make-routes [] (md/deferred))
                          (map first)
                          set)
                     :script/end)))

    (let [make-sid #(repeatedly 3 (comp str random-uuid))
          sid (make-sid)]
      (testing "sets script exit for same build"
        (let [exit (md/deferred)
              r (mmc/router (sut/make-routes sid exit))]
          (is (nil? (->  {:type :script/end
                          :sid sid}
                         (r)
                         first
                         :result)))
          (is (realized? exit))))

      (testing "is ignored for other build"
        (let [exit (md/deferred)
              r (mmc/router (sut/make-routes sid exit))]
          (is (nil? (->  {:type :script/end
                          :sid (make-sid)}
                         (r)
                         first
                         :result)))
          (is (not (realized? exit))))))))
