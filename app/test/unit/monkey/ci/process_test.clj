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
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

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
  (let [server-args (atom nil)
        server-closed? (atom nil)
        test-build {:script
                    {:script-dir "test-dir"}}
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

      (testing "api server"
        (is (nil? (reset! server-args nil)))
        (is (nil? (reset! server-closed? nil)))
        (is (some? (sut/execute! {} (-> test-rt
                                        (assoc :config {:runner {:api {:port 6543}}})))))
        
        (testing "started and stopped"
          (is (some? @server-args))
          (is (true? @server-closed?)))

        (testing "passes required config to server"
          (is (= 6543 (-> @server-args second :port))))))))

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
  (testing "adds build to config"
    (let [build {:build-id "test-build"}
          e (sut/child-config build
                              (-> trt/empty-runtime
                                  (trt/set-config {:config-key "value"}))
                              {})]
      (is (= build (:build e)))
      (is (= "value" (:config-key e)))))

  (testing "adds api token"
    (is (not-empty (-> (sut/child-config {} (trt/test-runtime) {})
                       (get-in [:api :token])))))

  (testing "api token contains build sid in sub"
    (let [sid (repeatedly 3 (comp str random-uuid))
          build {:sid sid}
          payload (-> (sut/child-config build (trt/test-runtime) {})
                      (get-in [:api :token])
                      (h/parse-token-payload))]
      (is (= (sid/serialize-sid sid) (:sub payload)))))

  (testing "adds api server port and ip address"
    (let [conf (sut/child-config {} (trt/test-runtime) {:port 1234})]
      (is (string? (get-in conf [:api :address])))
      (is (= 1234 (get-in conf [:api :port])))))

  (testing "no token if no jwk keys configured"
    (is (nil? (-> (sut/child-config {} trt/empty-runtime {})
                  (get-in [:api :token])))))

  (testing "does not overwrite token if no jwk config"
    (is (= "test-token"
           (-> (sut/child-config {}
                                 (-> trt/empty-runtime
                                     (trt/set-config {:api {:token "test-token"}}))
                                 {})
               (get-in [:api :token])))))

  (testing "overwrites event config with runner settings"
    (let [rt (-> trt/empty-runtime
                 (trt/set-config
                  {:events
                   {:type :manifold}
                   :runner
                   {:events
                    {:type :zmq}}}))]
      (is (= :zmq (-> (sut/child-config {} rt {})
                      :events
                      :type))))))
