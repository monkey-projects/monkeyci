(ns monkey.ci.runners.local-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.git :as git]
            [monkey.ci.runners.local :as sut]
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
    (let [{:keys [enter] :as i} (sut/save-workspace dir)]
      (is (keyword? (:name i)))
      (testing "copies build checkout dir into destination"
        ))))

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

(deftest build-pending
  (testing "returns `build/initializing` event"
    (let [build {:status :pending}
          r (sut/build-pending {:event
                                {:type :build/pending
                                 :build build}})]
      (is (= :build/initializing (:type r)))
      (is (= :initializing (-> r :build :status))))))
