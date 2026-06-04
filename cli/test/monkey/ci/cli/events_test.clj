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

(defn- get-chan [^monkey.mailman.core_async.CoreAsyncBroker tm]
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
  (let [mailman (test-mailman)
        base-conf (-> {:event
                       {:type :build/pending}}
                      (emi/set-mailman mailman)
                      (sut/set-api {:url "http://localhost:1234"
                                    :token "test-token"})
                      (sut/set-child-opts {:log-config "test-config.xml"
                                           :lib-coords {:mvn/version "test"}})
                      (sut/set-build-opts {:filter ["test-filter"]}))]

    (testing "fails if script dir not found"
      (is (thrown? Exception (-> base-conf
                                 (assoc-in [:event :build] {:checkout-dir "/nonexisting"})
                                 (sut/prepare-child-cmd)))))
    (fs/with-temp-dir [dir]
      (is (some? (fs/create-dir (fs/path dir ".monkeyci"))))
      (with-redefs [fs/which (constantly "/usr/bin/bb")]
        (let [build {:checkout-dir dir}
              r (-> base-conf
                    (assoc-in [:event :build] build)
                    (sut/prepare-child-cmd))]
          (testing "starts child process command"
            (testing "in script dir"
              (is (= (str dir "/.monkeyci") (str (:dir r)))))

            (testing "runs bb cmd"
              (is (re-matches #".*bb$" (-> r :cmd first))))

            (testing "generates custom `bb.edn`"
              (let [[_ p :as c] (->> r :cmd (rest) (take 2))]
                (is (= "--config" (first c)))
                (is (fs/exists? p))))

            (testing "exit fn fires `build/end`"
              (let [exit-fn (:exit-fn r)]
                (is (fn? exit-fn))
                (is (some? (exit-fn {:exit ::process-result})))
                (let [p (wait-for-posted mailman)]
                  (is (not (= :timeout p)))
                  (is (= :build/end (:type p)))))))))

      (testing "fails when no `bb` found"
        (with-redefs [fs/which (constantly nil)]
          (is (thrown? Exception (sut/prepare-child-cmd
                                  (-> base-conf
                                      (assoc-in [:event :build :checkout-dir] dir))))))))

    (testing "clojure runner"
      (fs/with-temp-dir [dir]
        (let [sd (fs/create-dir (fs/path dir ".monkeyci"))]
          (is (some? sd))
          (let [build {:checkout-dir (str dir)}
                r (-> base-conf
                      (assoc-in [::sut/child-opts :runner] :clj)
                      (assoc-in [:event :build] build)
                      (sut/prepare-child-cmd))]
            (testing "runs clojure")

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
                         (get-in deps [:aliases :monkeyci/build :extra-deps 'com.monkeyci/script]))))

                (testing "with m2 cache dir")))))))))

(deftest generate-bb-conf
  (let [r (-> {:event
               {:build
                {:org-id "a"
                 :repo-id "b"
                 :build-id "c"}}}
              (sut/set-child-opts {:lib-coords {:mvn/version "test"}})
              (sut/set-api {:url "http://test"
                            :token "test-token"})
              (sut/generate-bb-conf))]
    (testing "returns map"
      (is (map? r)))

    (testing "includes monkeyci lib"
      (is (= "test" (get-in r [:deps 'com.monkeyci/script :mvn/version]))))

    (testing "passes api url and token"
      (is (= "http://test"
             (get-in r [:tasks 'script :exec-args :url])))
      (is (= "test-token"
             (get-in r [:tasks 'script :exec-args :token]))))

    (testing "passes sid from build"
      (is (= "a/b/c"
             (get-in r [:tasks 'script :exec-args :sid])))))

  (testing "merges in existing `bb.edn`"
    (fs/with-temp-dir [dir]
      (is (nil? (spit (fs/file
                       (fs/create-dir (fs/path dir ".monkeyci"))
                       "bb.edn")
                      {:deps {'test-lib {:mvn/version "test-version"}}})))
      (let [r (sut/generate-bb-conf
               {:event
                {:build
                 {:checkout-dir dir}}})]
        (is (= "test-version" (get-in r [:deps 'test-lib :mvn/version])))))))

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

(deftest delete-work-dir-test
  (testing "has a keyword name"
    (is (keyword? (:name (sut/delete-work-dir {})))))

  (testing "deletes work-dir on leave when clean is `true`"
    (fs/with-temp-dir [ws-dir]
      (let [conf (-> {}
                     (cc/set-clean true)
                     (cc/set-work-dir ws-dir))
            ctx  {:result {:status :success}}
            {:keys [leave]} (sut/delete-work-dir conf)]
        (is (fs/exists? ws-dir) "work-dir must exist before leave")
        (leave ctx)
        (is (not (fs/exists? ws-dir)) "work-dir must be deleted after leave"))))

  (testing "does NOT delete work-dir on leave when clean is `false`"
    (fs/with-temp-dir [ws-dir]
      (let [conf (-> {}
                     (cc/set-clean false)
                     (cc/set-work-dir ws-dir))
            ctx {:result {:status :success}}
            {:keys [leave]} (sut/delete-work-dir conf)]
        (leave ctx)
        (is (fs/exists? ws-dir) "work-dir must NOT be deleted when --no-clean"))))

  (testing "does nothing when work-dir path is `nil`"
    (let [conf {}
          ctx  {:result {:status :success}}
          {:keys [leave]} (sut/delete-work-dir conf)]
      ;; Should not throw
      (is (map? (leave ctx)))))

  (testing "does not delete work dir if build failed"
    (fs/with-temp-dir [ws-dir]
      (let [conf (-> {}
                     (cc/set-clean true)
                     (cc/set-work-dir ws-dir))
            ctx  {:result
                  {:status :error}}
            {:keys [leave]} (sut/delete-work-dir conf)]
        (is (fs/exists? ws-dir) "work-dir must exist before leave")
        (leave ctx)
        (is (fs/exists? ws-dir) "work-dir must not be deleted after leave")))))

(deftest make-routes-workspace-test
  (testing "save-workspace is wired into :build/pending"
    (let [routes (into {} (sut/make-routes {} (test-mailman)))]
      (is (has-interceptor? routes :build/pending ::sut/save-workspace))))

  (testing "delete-work-dir is wired into :build/end"
    (let [routes (into {} (sut/make-routes {} (test-mailman)))]
      (is (has-interceptor? routes :build/end ::sut/delete-work-dir)))))

