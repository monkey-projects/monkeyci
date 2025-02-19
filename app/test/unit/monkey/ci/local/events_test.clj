(ns monkey.ci.local.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka
             [fs :as fs]
             [process :as bp]]
            [monkey.ci.git :as git]
            [monkey.ci.local.events :as sut]
            [monkey.ci.helpers :as h]))

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
        (is (= ::test-cmd (-> {:result {:cmd ::test-cmd}}
                              (leave)
                              (sut/get-process))))))))

(deftest no-result
  (let [{:keys [leave] :as i} sut/no-result]
    (is (keyword? (:name i)))
    
    (testing "`leave` removes result from context"
      (is (nil? (-> {:result ::test-result}
                    (leave)
                    :result))))))

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
               :build/start
               :build/end]
        routes (->> (sut/make-routes {})
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))

    (testing "`build/pending`"
      (testing "with git config"
        (testing "does git checkout"
          (is (-> {:build
                   {:git {:url "test-url"}}}
                  (sut/make-routes)
                  (has-interceptor? :build/pending ::sut/checkout)))))

      (testing "without git config"
        (testing "does no git checkout"
          (is (not (-> {:build {}}
                       (sut/make-routes)
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
        r (sut/prepare-child-cmd
           {:event
            {:type :build/pending
             :build build}})]
    (testing "starts child process command"
      (testing "in script dir"
        (is (= "/test/checkout/.monkeyci" (:dir r))))

      (testing "runs clojure"
        (is (= "clojure" (-> r :cmd first)))))))
