(ns monkey.ci.process-test
  (:require [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as l]
             [containers]
             [process :as sut]
             [script :as script]]
            [monkey.ci.utils :as u]
            [monkey.ci.build.core :as bc]
            [monkey.ci.web.script-api :as sa]
            [monkey.ci.helpers :as h]))

(def cwd (u/cwd))

(defn example [subdir]
  (.getAbsolutePath (io/file cwd "examples" subdir)))

(deftest run
  (with-redefs [sut/exit! (constantly nil)]
    (testing "executes script with given args"
      (let [captured-args (atom nil)]
        (with-redefs [script/exec-script! (fn [args]
                                            (reset! captured-args args)
                                            bc/success)]
          (is (nil? (sut/run {:key :test-args})))
          (is (= {:key :test-args}
                 (-> @captured-args
                     :config
                     :args))))))

    (testing "merges args with env vars"
      (let [captured-args (atom nil)]
        (with-redefs [script/exec-script! (fn [args]
                                            (reset! captured-args args)
                                            bc/success)]
          (is (nil? (sut/run
                      {:key :test-args}
                      {:monkeyci-containers-type "podman"
                       :monkeyci-api-socket "/tmp/test.sock"})))
          (is (= {:type :podman} (:containers @captured-args)))
          (is (= {:socket "/tmp/test.sock"} (get-in @captured-args [:config :api])))
          (is (= {:key :test-args} (get-in @captured-args [:config :args]))))))))

(deftest ^:slow execute-slow!
  (let [rt {:public-api sa/local-api
            :config {:dev-mode true}
            :logging {:maker (l/make-logger {:logging {:type :inherit}})}}]
    
    (testing "executes build script in separate process"
      (is (zero? (-> rt
                     (assoc :build {:script-dir (example "basic-clj")
                                    :build-id (u/new-build-id)})
                     sut/execute!
                     deref
                     :exit))))

    (testing "fails when script fails"
      (is (pos? (-> rt
                    (assoc :build {:script-dir (example "failing")
                                   :build-id (u/new-build-id)})
                    sut/execute!
                    deref
                    :exit))))

    (testing "fails when script not found"
      (is (thrown? java.io.IOException (-> rt
                                           (assoc :build {:script-dir (example "non-existing")})
                                           (sut/execute!)))))))

(defn- find-arg
  "Finds the argument value for given key"
  [d k]
  (->> d
       deref
       :process
       :args
       :cmd
       (drop-while (partial not= (str k)))
       (second)))

(defrecord TestServer []
  org.httpkit.server.IHttpServer
  (-server-stop! [s opts]
    (future nil)))

(deftype FakeProcess [exitValue])

(deftest execute!
  (let [server-started? (atom nil)]
    (with-redefs [bp/process (fn [{:keys [exit-fn] :as args}]
                               (do
                                 (when (fn? exit-fn)
                                   (exit-fn {:proc (->FakeProcess 1234)
                                             :args args}))))
                  sa/start-server (fn [& args]
                                    (reset! server-started? true)
                                    (->TestServer))]

      (testing "returns deferred"
        (is (md/deferred? (sut/execute!
                           {:args {:dev-mode true}
                            :build {:script-dir (example "failing")}}))))
      
      (testing "returns exit code"
        (is (= 1234 (:exit @(sut/execute!
                             {:args {:dev-mode true}
                              :build {:script-dir (example "failing")}})))))

      (testing "invokes in script dir"
        (is (= "test-dir" (-> @(sut/execute! {:build {:script-dir "test-dir"}})
                              :process
                              :args
                              :dir))))

      (testing "passes checkout dir in edn"
        (is (= "\"work-dir\"" (-> {:build {:checkout-dir "work-dir"}}
                                  (sut/execute!)
                                  (find-arg :checkout-dir)))))

      (testing "passes script dir in edn"
        (is (= "\"script-dir\"" (-> {:build {:script-dir "script-dir"}}
                                    (sut/execute!)
                                    (find-arg :script-dir)))))

      (testing "passes pipeline in edn"
        (is (= "\"test-pipeline\"" (-> {:build {:pipeline "test-pipeline"}}
                                       (sut/execute!)
                                       (find-arg :pipeline)))))

      (testing "starts script api server"
        (is (some? (sut/execute! {})))
        (is (true? @server-started?)))

      (testing "passes socket path in env"
        (is (string? (-> {:script-dir "test-dir"}
                         (sut/execute!)
                         (deref)
                         :process
                         :args
                         :extra-env
                         :monkeyci-api-socket)))))))

(deftest process-env
  (testing "passes build id"
    (is (= "test-build" (-> {:build {:build-id "test-build"}}
                            (sut/process-env "test-socket")
                            :monkeyci-build-build-id))))

  (testing "passes build sid in serialized fashion"
    (is (= "a/b/c" (-> {:build {:sid ["a" "b" "c"]}}
                       (sut/process-env "test-socket")
                       :monkeyci-build-sid))))
  
  (testing "sets `LC_CTYPE` to `UTF-8` for git clones"
    (is (= "UTF-8" (-> {}
                       (sut/process-env "test-socket")
                       :lc-ctype))))

  (testing "passes serialized config"
    (let [env (-> {:config
                   {:logging {:type :file
                              :dir "test-dir"}}}
                  (sut/process-env "test-socket"))]
      (is (= "file" (:monkeyci-logging-type env)))
      (is (= "test-dir" (:monkeyci-logging-dir env))))))

(deftest generate-deps
  (testing "adds log config file, relative to work dir if configured"
    (h/with-tmp-dir dir
      (let [logfile (io/file dir "logback-test.xml")]
        (is (nil? (spit logfile "test file")))
        (is (= (str "-Dlogback.configurationFile=" logfile)
               (-> {:config {:runner
                             {:type :child
                              :log-config "logback-test.xml"}
                             :work-dir dir}}
                   (sut/generate-deps)
                   :aliases
                   :monkeyci/build
                   :jvm-opts
                   first))))))

  (testing "adds log config file as absolute path"
    (h/with-tmp-dir dir
      (let [logfile (io/file dir "logback-test.xml")]
        (is (nil? (spit logfile "test file")))
        (is (= (str "-Dlogback.configurationFile=" logfile)
               (-> {:config {:runner
                             {:type :child
                              :log-config (.getAbsolutePath logfile)}
                             :work-dir "other"}}
                   (sut/generate-deps)
                   :aliases
                   :monkeyci/build
                   :jvm-opts
                   first)))))))
