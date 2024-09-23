(ns monkey.ci.process-test
  (:require [aleph.http :as http]
            [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.spec.alpha :as s]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as l]
             [containers]
             [process :as sut]
             [script :as script]
             [sid :as sid]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.config.script :as cos]
            [monkey.ci.spec
             [common :as sc]
             [events :as se]
             [script :as ss]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

(deftest run
  (let [build {:build-id "test-build"}
        config (-> cos/empty-config
                   (cos/set-build build)
                   (cos/set-api {:url "http://test"
                                 :token "test-token"}))]    
    (with-redefs [sut/exit! (constantly nil)]
      (testing "parses arg as config file"
        (h/with-tmp-dir dir
          (let [captured-args (atom nil)
                config-file (io/file dir "config.edn")]
            (with-redefs [script/exec-script! (fn [args]
                                                (reset! captured-args args)
                                                bc/success)]
              (is (nil? (spit config-file (pr-str config))))
              (is (nil? (sut/run {:config-file (.getAbsolutePath config-file)})))
              (is (= build
                     (-> @captured-args
                         :build))))))))))

(deftype FakeProcess [exitValue])

(deftest execute!
  (let [server-args (atom nil)
        server-closed? (atom nil)
        test-build {:script
                    {:script-dir "test-dir"}
                    :sid (h/gen-build-sid)}
        test-rt (-> (trt/test-runtime)
                    (merge {:args {:dev-mode true}}))]
    (with-redefs [bp/process (fn [{:keys [exit-fn] :as args}]
                               (do
                                 (when (fn? exit-fn)
                                   (exit-fn {:proc (->FakeProcess 1234)
                                             :args args}))))
                  http/start-server (fn [& args]
                                      (reset! server-args args)
                                      (h/->FakeServer server-closed?))]

      (testing "returns deferred"
        (is (md/deferred? (sut/execute!
                           test-build test-rt))))
      
      (testing "returns exit code"
        (is (= 1234 (:exit @(sut/execute!
                             test-build test-rt)))))

      (testing "invokes in script dir"
        (is (= "test-dir" (-> @(sut/execute! test-build test-rt)
                              :process
                              :args
                              :dir))))

      (testing "passes config file in edn"
        (is (re-matches #"^\{:config-file \".*\.edn\"}" 
                        (-> {:checkout-dir "work-dir"}
                            (sut/execute! test-rt)
                            (deref)
                            :process
                            :args
                            :cmd
                            last))))

      (testing "posts `script/initializing` event"
        (is (some? @(sut/execute! test-build test-rt)))
        (let [evt (->> test-rt
                       :events
                       (h/received-events)
                       (h/first-event-by-type :script/initializing))]
          (is (some? evt))
          (is (s/valid? ::se/event evt)))))))

(deftest generate-deps
  (testing "adds log config file, relative to work dir if configured"
    (h/with-tmp-dir dir
      (let [logfile (io/file dir "logback-test.xml")]
        (is (nil? (spit logfile "test file")))
        (is (= (str "-Dlogback.configurationFile=" logfile)
               (->> {:config {:runner
                              {:type :child
                               :log-config "logback-test.xml"}
                              :work-dir dir}}
                    (sut/generate-deps {})
                    :aliases
                    :monkeyci/build
                    :jvm-opts
                    first))))))

  (testing "log config"
    (letfn [(get-jvm-opts [deps]
              (-> deps
                  :aliases
                  :monkeyci/build
                  :jvm-opts
                  first))]
      (testing "adds log config file as absolute path"
        (h/with-tmp-dir dir
          (let [logfile (io/file dir "logback-test.xml")]
            (is (nil? (spit logfile "test file")))
            (is (= (str "-Dlogback.configurationFile=" logfile)
                   (->> {:config {:runner
                                  {:type :child
                                   :log-config (.getAbsolutePath logfile)}
                                  :work-dir "other"}}
                        (sut/generate-deps {})
                        (get-jvm-opts)))))))

      (testing "adds log config file from script dir"
        (h/with-tmp-dir dir
          (let [logfile (io/file dir "logback.xml")]
            (is (nil? (spit logfile "test file")))
            (is (= (str "-Dlogback.configurationFile=" logfile)
                   (-> {:script
                        {:script-dir dir}}
                       (sut/generate-deps {})
                       (get-jvm-opts)))))))))  

  (testing "adds script dir as paths"
    (is (= ["test-dir"] (-> {:script {:script-dir "test-dir"}}
                            (sut/generate-deps {})
                            :paths)))))

(deftest child-config
  (testing "satisfies spec"
    (is (s/valid? ::ss/config (sut/child-config {:build-id "test-build"}
                                                {:port 1234
                                                 :token "test-token"}))))
  
  (testing "adds build to config"
    (let [build {:build-id "test-build"}
          e (sut/child-config build {})]
      (is (= build (cos/build e)))))

  (testing "adds api server url and ip address"
    (let [conf (sut/child-config {}  {:port 1234})]
      (is (sc/url? (:url (cos/api conf))))
      (is (= 1234 (:port (cos/api conf))))))

  (testing "adds api server token"
    (let [conf (sut/child-config {} {:token "test-token"})]
      (is (= "test-token" (:token (cos/api conf)))))))
