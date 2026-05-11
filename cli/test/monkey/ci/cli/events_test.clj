(ns monkey.ci.cli.events-test
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.cli
             [build :as b]
             [config :as cc]
             [events :as sut]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]
            [monkey.mailman.core-async :as mmca]))

(defn edn-> [s]
  (edn/read-string s))

(deftest add-log-dir
  (fs/with-temp-dir [dir]
    (let [log-dir (fs/path dir "logs")
          {:keys [enter] :as i} (sut/add-log-dir log-dir)]
      (testing "adds log dir to context"
        (is (= log-dir (-> {}
                           (enter)
                           (sut/get-log-dir)))))

      (testing "ensures log dir exists"
        (is (fs/exists? log-dir))))))

(deftest add-job-to-state
  (let [{:keys [enter] :as i} sut/add-job-to-state]
    (is (keyword? (:name i)))
    
    (testing "saves job state and result in state"
      (is (= {:status :success
              :result :ok}
             (-> {:event
                  {:job-id "test-job"
                   :status :success
                   :result :ok}}
                 (enter)
                 (emi/get-state)
                 :jobs
                 (get "test-job")))))))

(deftest add-build-opts
  (let [{:keys [enter] :as i} (sut/add-build-opts
                               (cc/set-job-filter {} ["test-filter"]))]
    (is (keyword? (:name i)))

    (testing "sets job filter in context"
      (is (= ["test-filter"]
             (-> {}
                 (enter)
                 (sut/get-build-opts)
                 :filter))))))

(defn- has-interceptor? [routes evt id]
  (contains? (->> routes
                  (into {})
                  evt
                  first
                  :interceptors
                  (map :name)
                  (set))
             id))

(defn- test-mailman []
  (mmca/core-async-broker))

(defn- get-chan [tm]
  (.chan tm))

(defn- wait-for-posted
  "Waits for next posted event"
  [tm]
  (let [c (get-chan tm)
        t (ca/timeout 1000)]
    (ca/pipe c t)
    (or (ca/<!! t) ::timeout)))

(deftest make-routes
  (let [types [:build/pending
               :build/initializing
               :build/end
               :job/end]
        mailman (test-mailman)
        routes (->> (sut/make-routes {} mailman)
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))

    (testing "`build/pending`"
      #_(testing "with git config"
        (testing "does git checkout"
          (is (-> {:build
                   {:git {:url "test-url"}}}
                  (sut/make-routes mailman)
                  (has-interceptor? :build/pending ::sut/checkout)))))

      (testing "without git config"
        (testing "does no git checkout"
          (is (not (-> {:build {}}
                       (sut/make-routes mailman)
                       (has-interceptor? :build/pending ::sut/checkout)))))))))

(deftest make-build-init-evt
  (let [build {:status :pending}
        r (sut/make-build-init-evt
           {:event
            {:type :build/pending
             :build build}})]
    (testing "returns `build/initializing` event"
      (is (= :build/initializing (:type r)))
      (is (= :initializing (-> r :build :status))))))

(deftest prepare-child-cmd
  (let [build {:checkout-dir "/test/checkout"}
        mailman (test-mailman)
        r (-> {:event
               {:type :build/pending
                :build build}}
              (emi/set-mailman mailman)
              (sut/set-api {:url "http://localhost:1234"
                            :token "test-token"})
              (sut/set-child-opts {:log-config "test-config.xml"
                                   :lib-coords {:mvn/version "test"}})
              (sut/set-build-opts {:filter ["test-filter"]})
              (sut/prepare-child-cmd))]
    (testing "starts child process command"
      (testing "in script dir"
        (is (= "/test/checkout/.monkeyci" (str (:dir r)))))

      (testing "runs clojure"
        (is (= "clojure" (-> r :cmd first))))

      (testing "passes config"
        (let [conf (-> r :cmd last (edn->) :config)]
          (testing "with build script dir"
            (is (= (str (:dir r)) (b/script-dir (sc/build conf)))))
          
          (testing "with api settings"
            (let [api (sc/api conf)]
              (is (not-empty api))
              (is (= "http://localhost:1234" (:url api)))
              (is (= "test-token" (:token api)))))

          (testing "with job filter"
            (is (= ["test-filter"] (sc/job-filter conf))))))

      (testing "passes deps"
        (let [deps (-> r :cmd (nth 2) (edn->))]
          (testing "with log config from child opts"
            (is (= "-Dlogback.configurationFile=test-config.xml"
                   (-> deps
                       (get-in [:aliases :monkeyci/build :jvm-opts])
                       first))))

          (testing "with lib coords"
            (is (= {:mvn/version "test"}
                   (get-in deps [:aliases :monkeyci/build :extra-deps 'com.monkeyci/app]))))

          (testing "with m2 cache dir"))))

    (testing "exit fn fires `build/end`"
      (let [exit-fn (:exit-fn r)]
        (is (fn? exit-fn))
        (is (some? (exit-fn {:exit ::process-result})))
        (let [p (wait-for-posted mailman)]
          (is (not (= :timeout p)))
          (is (= :build/end (:type p))))))))

(deftest build-end
  (testing "returns build with jobs from state"
    (let [build {:build-id "test-build"}]
      (is (= {:build-id "test-build"
              :jobs {"test-job" {:status :success}}}
             (-> {:event {:build build}}
                 (sut/update-job-in-state "test-job" assoc :status :success)
                 (sut/build-end)))))))

(deftest deliver-end
  (let [p (promise)
        {:keys [leave] :as i} (sut/deliver-end p)]
    (is (keyword? (:name i)))

    (testing "`leave` delivers result to configured end promise"
      (is (some? (-> {}
                     (sut/set-result ::test-result)
                     (leave))))
      (is (realized? p))
      (is (= ::test-result (deref p 500 ::timeout))))))

(deftest save-workspace-test
  (fs/with-temp-dir [src-dir]
    (fs/with-temp-dir [dest-root]
      (let [dest    (fs/path dest-root "workspace")
            ;; Populate the source with some files
            _       (spit (str (fs/path src-dir "file.txt")) "hello")
            _       (fs/create-dirs (fs/path src-dir "subdir"))
            _       (spit (str (fs/path src-dir "subdir" "nested.txt")) "world")
            ctx     {:event {:build {:checkout-dir (str src-dir)}}}
            {:keys [enter] :as i} (sut/save-workspace dest)]

        (testing "has a keyword name"
          (is (keyword? (:name i))))

        (let [result (enter ctx)]
          (testing "copies checkout dir contents to dest"
            (is (fs/exists? (fs/path dest "file.txt")))
            (is (fs/exists? (fs/path dest "subdir" "nested.txt"))))

          (testing "stores workspace path in context state"
            (is (= (str dest) (sut/get-workspace result))))

          (testing "returns a context map"
            (is (map? result))))))))

(deftest save-workspace-no-checkout-dir-test
  (fs/with-temp-dir [dest-root]
    (let [dest  (fs/path dest-root "workspace")
          ctx   {:event {:build {}}}           ; no :checkout-dir
          {:keys [enter]} (sut/save-workspace dest)]

      (testing "returns context unchanged when no checkout-dir is present"
        (let [result (enter ctx)]
          (is (map? result))
          (is (nil? (sut/get-workspace result))))))))

(deftest delete-workspace-test
  (testing "deletes workspace on leave when no-clean is false"
    (fs/with-temp-dir [ws-dir]
      (let [conf    {}                       ; no-clean not set → should delete
            ctx     (-> {}
                        (emi/update-state assoc :workspace (str ws-dir)))
            {:keys [leave]} (sut/delete-workspace conf)]
        (is (fs/exists? ws-dir) "workspace must exist before leave")
        (leave ctx)
        (is (not (fs/exists? ws-dir)) "workspace must be deleted after leave"))))

  (testing "does NOT delete workspace on leave when no-clean is true"
    (fs/with-temp-dir [ws-dir]
      (let [conf    (cc/set-no-clean {} true)
            ctx     (-> {}
                        (emi/update-state assoc :workspace (str ws-dir)))
            {:keys [leave]} (sut/delete-workspace conf)]
        (leave ctx)
        (is (fs/exists? ws-dir) "workspace must NOT be deleted when --no-clean"))))

  (testing "does nothing when workspace path is nil"
    (let [conf {}
          ctx  {}
          {:keys [leave]} (sut/delete-workspace conf)]
      ;; Should not throw
      (is (map? (leave ctx)))))

  (testing "has a keyword name"
    (is (keyword? (:name (sut/delete-workspace {}))))))

(deftest make-routes-workspace-test
  (testing "save-workspace is wired into :build/pending"
    (let [routes (into {} (sut/make-routes {} (test-mailman)))]
      (is (has-interceptor? routes :build/pending ::sut/save-workspace))))

  (testing "delete-workspace is wired into :build/end"
    (let [routes (into {} (sut/make-routes {} (test-mailman)))]
      (is (has-interceptor? routes :build/end ::sut/delete-workspace)))))

