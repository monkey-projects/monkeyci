(ns monkey.ci.process-test
  (:require [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci
             [events :as events]
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
  (testing "executes script with given args"
    (let [captured-args (atom nil)]
      (with-redefs [script/exec-script! (fn [args]
                                          (reset! captured-args args)
                                          bc/success)]
        (is (nil? (sut/run {:key :test-args})))
        (is (= {:key :test-args} (-> @captured-args
                                     (select-keys [:key])))))))

  (testing "merges args with env vars"
    (let [captured-args (atom nil)]
      (with-redefs [script/exec-script! (fn [args]
                                          (reset! captured-args args)
                                          bc/success)]
        (is (nil? (sut/run
                    {:key :test-args}
                    {:monkeyci-containers-type "docker"
                     :monkeyci-event-socket "/tmp/test.sock"})))
        (is (= {:containers {:type :docker}
                :event-socket "/tmp/test.sock"}
               (-> @captured-args
                   (select-keys [:containers
                                 :event-socket]))))))))

(deftest ^:slow execute-slow!
  (let [base-ctx {:public-api sa/local-api
                  :args {:dev-mode true}}]
    
    (testing "executes build script in separate process"
      (is (zero? (-> base-ctx
                     (assoc :build {:script-dir (example "basic-clj")})
                     sut/execute!
                     deref
                     :exit))))

    (testing "fails when script fails"
      (is (pos? (-> base-ctx
                    (assoc :build {:script-dir (example "failing")})
                    sut/execute!
                    deref
                    :exit))))

    (testing "fails when script not found"
      (is (thrown? java.io.IOException (-> base-ctx
                                           (assoc :build {:script-dir (example "non-existing")})
                                           (sut/execute!)))))))

(defn- find-arg
  "Finds the argument value for given key"
  [{:keys [args]} k]
  (->> args
       :cmd
       (drop-while (partial not= (str k)))
       (second)))

(defrecord TestServer []
  org.httpkit.server.IHttpServer
  (-server-stop! [s opts]
    (future nil)))

(deftest execute!
  (let [server-started? (atom nil)]
    (with-redefs [bp/process (fn [{:keys [exit-fn] :as args}]
                               (do
                                 (when (fn? exit-fn)
                                   (exit-fn {}))
                                 {:args args
                                  :exit 1234}))
                  sa/start-server (fn [& args]
                                    (reset! server-started? true)
                                    (->TestServer))]
      
      (testing "returns exit code"
        (is (= 1234 (:exit (sut/execute!
                            {:args {:dev-mode true}
                             :build {:script-dir (example "failing")}})))))

      (testing "invokes in script dir"
        (is (= "test-dir" (-> (sut/execute! {:build {:script-dir "test-dir"}})
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
        (let [bus (events/make-bus)]
          (is (string? (-> {:script-dir "test-dir"
                            :event-bus bus}
                           (sut/execute!)
                           :args
                           :extra-env
                           :monkeyci-api-socket))))))))

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

  (testing "passes log config, without maker"
    (let [env (-> {:logging {:type :file
                             :dir "test-dir"
                             :maker (constantly :error)}}
                  (sut/process-env "test-socket"))]
      (is (= "file" (:monkeyci-logging-type env)))
      (is (= "test-dir" (:monkeyci-logging-dir env)))
      (is (not (contains? env :monkeyci-logging-maker)))))

  (testing "passes container props"
    (let [env (-> {:containers {:type :podman
                                :platform "linux/amd64"}}
                  (sut/process-env "test-socket"))]
      (is (= "podman" (:monkeyci-containers-type env)))
      (is (= "linux/amd64" (:monkeyci-containers-platform env))))))
