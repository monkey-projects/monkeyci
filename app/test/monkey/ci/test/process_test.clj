(ns monkey.ci.test.process-test
  (:require [babashka.process :as bp]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci
             [events :as events]
             [process :as sut]
             [script :as script]]
            [monkey.ci.utils :as u]
            [monkey.ci.build.core :as bc]
            [monkey.ci.test.helpers :as h]))

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
        (is (= {:containers {:type  :docker}
                :event-socket "/tmp/test.sock"}
               (-> @captured-args
                   (select-keys [:containers
                                 :event-socket]))))))))

(deftest ^:slow execute-slow!
  (testing "executes build script in separate process"
    (is (zero? (-> {:build {:script-dir (example "basic-clj")}
                    :args {:dev-mode true}}
                   sut/execute!
                   deref
                   :exit))))

  (testing "fails when script fails"
    (is (pos? (-> {:build {:script-dir (example "failing")}
                   :args {:dev-mode true}}
                  sut/execute!
                  deref
                  :exit))))

  (testing "fails when script not found"
    (is (thrown? java.io.IOException (sut/execute!
                                      {:args {:dev-mode true}
                                       :build {:script-dir (example "non-existing")}})))))

(defn- find-arg
  "Finds the argument value for given key"
  [{:keys [args]} k]
  (->> args
       :cmd
       (drop-while (partial not= (str k)))
       (second)))

(deftest execute!
  (with-redefs [bp/process (fn [{:keys [exit-fn] :as args}]
                             (do
                               (when (fn? exit-fn)
                                 (exit-fn {}))
                               {:args args
                                :exit 1234}))]
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

    (testing "passes socket path in env when bus exists"
      (let [bus (events/make-bus)]
        (is (string? (-> {:script-dir "test-dir"
                          :event-bus bus}
                         (sut/execute!)
                         :args
                         :extra-env
                         :monkeyci-event-socket)))))

    (testing "no socket path when no bus exists"
      (is (nil? (-> {:build {:script-dir "test-dir"}}
                    (sut/execute!)
                    :args
                    :extra-env
                    :monkeyci-event-socket))))))
