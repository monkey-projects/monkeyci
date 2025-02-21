(ns monkey.ci.local.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka
             [fs :as fs]
             [process :as bp]]
            [manifold.deferred :as md]
            [monkey.ci
             [edn :as edn]
             [git :as git]]
            [monkey.ci.config.script :as cs]
            [monkey.ci.local
             [config :as lc]
             [events :as sut]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.mailman :as tm]))

(deftest checkout-src
  (let [{:keys [enter] :as i} sut/checkout-src]
    (is (keyword? (:name i)))
    
    (testing "`enter` clones using git settings"
      (with-redefs [git/clone+checkout (partial hash-map ::test-repo)]
        (is (= {::test-repo {:url "test-url"}}
               (-> {:event
                    {:build
                     {:git
                      {:url "test-url"}}}}
                   (enter)
                   (sut/get-git-repo))))))))

(deftest save-workspace
  (h/with-tmp-dir dir
    (let [src (fs/path dir "checkout")
          dest (fs/path dir "workspace")
          {:keys [enter] :as i} (sut/save-workspace dest)]
      (is (keyword? (:name i)))

      (testing "copies build checkout dir into destination"
        (is (some? (fs/create-dir src)))
        (is (some? (fs/create-dir dest)))
        (is (nil? (spit (str (fs/path src "test.txt")) "test file")))
        (is (= dest (-> (enter {:event
                                {:build
                                 {:checkout-dir (str src)}}})
                        (sut/get-workspace))))
        (is (fs/exists? (fs/path dest "test.txt")))))))

(deftest start-process
  (let [{:keys [leave] :as i} sut/start-process]
    (is (keyword? (:name i)))

    (testing "`leave` starts child process"
      (with-redefs [bp/process identity]
        (is (= ::test-cmd (-> {:result ::test-cmd}
                              (leave)
                              (sut/get-process))))))))

(deftest no-result
  (let [{:keys [leave] :as i} sut/no-result]
    (is (keyword? (:name i)))
    
    (testing "`leave` removes result from context"
      (is (nil? (-> {:result ::test-result}
                    (leave)
                    :result))))))

(deftest realize-ending
  (let [e (md/deferred)
        {:keys [leave] :as i} sut/realize-ending
        build (h/gen-build)]
    (is (keyword? (:name i)))
    
    (testing "`leave` sets result in ending"
      (is (map? (-> {:result build}
                    (sut/set-ending e)
                    (leave))))
      (is (= build (deref e 100 :timeout))))))

(deftest add-log-dir
  (h/with-tmp-dir dir
    (let [log-dir (fs/path dir "logs")
          {:keys [enter] :as i} (sut/add-log-dir log-dir)]
      (testing "adds log dir to context"
        (is (= log-dir (-> {}
                           (enter)
                           (sut/get-log-dir)))))

      (testing "ensures log dir exists"
        (is (fs/exists? log-dir))))))

(defn- has-interceptor? [routes evt id]
  (contains? (->> routes
                  (into {})
                  evt
                  first
                  :interceptors
                  (map :name)
                  (set))
             id))

(deftest make-routes
  (let [types [:build/pending
               :build/initializing
               :build/end]
        mailman (tm/test-component)
        routes (->> (sut/make-routes {} mailman)
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))

    (testing "`build/pending`"
      (testing "with git config"
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
        pr (md/deferred)
        mailman (tm/test-component)
        r (-> {:event
               {:type :build/pending
                :build build}}
              (sut/set-mailman mailman)
              (sut/set-api {:port 1234
                            :token "test-token"})
              (sut/prepare-child-cmd))]
    (testing "starts child process command"
      (testing "in script dir"
        (is (= "/test/checkout/.monkeyci" (:dir r))))

      (testing "runs clojure"
        (is (= "clojure" (-> r :cmd first))))

      (testing "passes config"
        (let [conf (-> r :cmd last (edn/edn->) :config)]
          (testing "with build"
            (is (= build (cs/build conf))))
          
          (testing "with api settings"
            (let [api (cs/api conf)]
              (is (not-empty api))
              (is (= "http://localhost:1234" (:url api)))
              (is (= "test-token" (:token api))))))))

    (testing "exit fn fires `build/end`"
      (let [exit-fn (:exit-fn r)]
        (is (fn? exit-fn))
        (is (some? (exit-fn ::process-result)))
        (is (= [:build/end] (->> (tm/get-posted mailman)
                                 (map :type))))))))

(deftest build-end
  (testing "returns build"
    (let [build (h/gen-build)]
      (is (= build (sut/build-end {:event {:build build}}))))))
