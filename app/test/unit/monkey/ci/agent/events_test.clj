(ns monkey.ci.agent.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [babashka.fs :as fs]
            [monkey.ci.agent.events :as sut]
            [monkey.ci.build :as b]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.spec.script :as ss]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]
            [monkey.mailman.core :as mmc]))

(deftest routes
  (let [evts [:build/queued :build/end]
        routes (sut/make-routes {})]
    (doseq [t evts]
      (testing (str "handles " t)
        (is (contains? (->> routes
                            (map first)
                            (set))
                       t)))))

  (testing "`build/queued`"
    (testing "starts build container"
      (let [procs (atom [])
            fake-proc {:name (:name emi/start-process)
                       :leave (fn [ctx]
                                (swap! procs conj (:result ctx))
                                ctx)}
            router (-> {}
                       (sut/make-routes)
                       (mmc/router)
                       (mmc/replace-interceptors [fake-proc]))
            build (h/gen-build)]
        (is (some? (router {:type :build/queued
                            :sid (b/sid build)
                            :build build})))
        (is (= 1 (count @procs)))))

    (testing "clones git repo")

    (testing "saves workspace")))

(deftest add-config
  (let [{:keys [enter] :as i} (sut/add-config ::config)]
    (is (keyword? (:name i)))
    
    (testing "`enter` sets config in context"
      (is (= ::config (-> (enter {})
                          (sut/get-config)))))))

(deftest prepare-build-cmd
  (h/with-tmp-dir dir
    (let [build (-> (h/gen-build)
                    (dissoc :script))
          broker (tm/test-component)
          cmd (-> {:event
                   {:build build
                    :sid (b/sid build)}}
                  (sut/set-config {:image "test-img"
                                   :work-dir dir
                                   :mailman broker})
                  (sut/prepare-build-cmd ))]
      (testing "executes podman"
        (is (= "podman" (-> cmd :cmd first))))

      (testing "runs clojure cmd"
        (is (contains? (set (:cmd cmd)) "clojure")))

      (testing "uses configured image"
        (is (contains? (set (:cmd cmd)) "test-img")))

      (testing "sets work dir to script dir"
        (is (= (str (apply fs/path dir (conj (b/sid build) "checkout/.monkeyci")))
               (:dir cmd))))

      (testing "on exit, fires `build/end` event"
        (let [on-exit (:on-exit cmd)]
          (is (fn? on-exit))
          (is (some? (on-exit {:exit 0})))
          (is (= [:build/end]
                 (->> (tm/get-posted broker)
                      (map :type)))))))))

(deftest generate-deps
  (let [deps (sut/generate-deps "test-dir" "test-version" {})]
    (testing "includes script dir"
      (is (some? (:paths deps))))

    (testing "includes config args"
      (is (some? (get-in deps [:aliases :monkeyci/build :exec-args]))))))

(deftest generate-script-config
  (testing "builds script config"
    (let [conf (sut/generate-script-config {:api {:port 1234
                                                  :token "very-secret"}}
                                           (h/gen-build))]
      (is (spec/valid? ::ss/config conf)
          (spec/explain-str ::ss/config conf)))))
