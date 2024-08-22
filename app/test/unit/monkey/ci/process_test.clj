(ns monkey.ci.process-test
  (:require [aleph.http :as http]
            [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as l]
             [containers]
             [process :as sut]
             [script :as script]
             [sid :as sid]]
            [monkey.ci.utils :as u]
            [monkey.ci.build.core :as bc]
            [monkey.ci.helpers :as h]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

(deftest run
  (with-redefs [sut/exit! (constantly nil)]
    (testing "parses arg as config file"
      (h/with-tmp-dir dir
        (let [captured-args (atom nil)
              config-file (io/file dir "config.edn")]
          (with-redefs [script/exec-script! (fn [args]
                                              (reset! captured-args args)
                                              bc/success)]
            (is (nil? (spit config-file (pr-str {:build {:build-id "test-build"}}))))
            (is (nil? (sut/run {:config-file (.getAbsolutePath config-file)})))
            (is (= {:build-id "test-build"}
                   (-> @captured-args
                       :build)))))))

    (testing "merges config with env vars"
      (h/with-tmp-dir dir
        (let [captured-args (atom nil)
              config-file (io/file dir "config.edn")]
          (with-redefs [script/exec-script! (fn [args]
                                              (reset! captured-args args)
                                              bc/success)]
            (is (nil? (spit config-file (pr-str {:build {:build-id "test-build"}}))))
            (is (nil? (sut/run
                        {:config-file (.getAbsolutePath config-file)}
                        {:monkeyci-containers-type "podman"
                         :monkeyci-api-socket "/tmp/test.sock"})))
            (is (= :podman (-> (:containers @captured-args)
                               :type)))
            (is (= {:socket "/tmp/test.sock"} (get-in @captured-args [:config :api])))))))))

(deftype FakeProcess [exitValue])

(deftest execute!
  (let [server-started? (atom nil)
        server-closed? (atom nil)]
    (with-redefs [bp/process (fn [{:keys [exit-fn] :as args}]
                               (do
                                 (when (fn? exit-fn)
                                   (exit-fn {:proc (->FakeProcess 1234)
                                             :args args}))))
                  http/start-server (fn [& _]
                                      (reset! server-started? true)
                                      (h/->FakeServer server-closed?))]

      (testing "returns deferred"
        (is (md/deferred? (sut/execute!
                           {:script
                            {:script-dir (example "failing")}}
                           {:args {:dev-mode true}}))))
      
      (testing "returns exit code"
        (is (= 1234 (:exit @(sut/execute!
                             {:script
                              {:script-dir (example "failing")}}
                             {:args {:dev-mode true}})))))

      (testing "invokes in script dir"
        (is (= "test-dir" (-> @(sut/execute! {:script
                                              {:script-dir "test-dir"}}
                                             {})
                              :process
                              :args
                              :dir))))

      (testing "passes config file in edn"
        (is (re-matches #"^\{:config-file \".*\.edn\"}" 
                        (-> {:checkout-dir "work-dir"}
                            (sut/execute! {})
                            (deref)
                            :process
                            :args
                            :cmd
                            last))))

      (testing "starts and stops api server"
        (is (nil? (reset! server-started? nil)))
        (is (nil? (reset! server-closed? nil)))
        (is (some? (sut/execute! {} {})))
        (is (true? @server-started?))
        (is (true? @server-closed?))))))

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

(deftest rt->config
  (testing "adds build to config"
    (let [build {:build-id "test-build"}
          e (sut/rt->config build {:config {:config-key "value"}})]
      (is (= build (:build e)))
      (is (= "value" (:config-key e)))))

  (testing "adds api token"
    (is (not-empty (-> (sut/rt->config {} (h/test-rt))
                       (get-in [:api :token])))))

  (testing "api token contains build sid in sub"
    (let [sid (repeatedly 3 (comp str random-uuid))
          build {:sid sid}
          payload (-> (sut/rt->config build (h/test-rt))
                      (get-in [:api :token])
                      (h/parse-token-payload))]
      (is (= (sid/serialize-sid sid) (:sub payload)))))

  (testing "no token if no jwk keys configured"
    (is (nil? (-> (sut/rt->config {} {})
                  (get-in [:api :token])))))

  (testing "does not overwrite token if no jwk config"
    (is (= "test-token"
           (-> (sut/rt->config {} {:config {:api {:token "test-token"}}})
               (get-in [:api :token])))))

  (testing "overwrites event config with runner settings"
    (let [rt {:config {:events
                       {:type :manifold}
                       :runner
                       {:events
                        {:type :zmq}}}}]
      (is (= :zmq (-> (sut/rt->config {} rt)
                      :events
                      :type))))))
