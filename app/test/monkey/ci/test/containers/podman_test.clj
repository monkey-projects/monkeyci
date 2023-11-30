(ns monkey.ci.test.containers.podman-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.process :as bp]
            [monkey.ci
             [containers :as mcc]
             [logging :as l]]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest run-container
  (with-redefs [bp/process (fn [args]
                             (future args))]
    
    (testing "starts podman process"   
      (h/with-tmp-dir dir
        (let [r (mcc/run-container
                 {:containers {:type :podman}
                  :build {:build-id "test-build"}
                  :work-dir dir
                  :step {:name "test-step"
                         :container/image "test-img"
                         :script ["first" "second"]}})]
          (is (map? r))
          (is (= "/usr/bin/podman" (-> r :cmd first)))
          (is (contains? (set (:cmd r))
                         "test-img"))
          (is (= "first && second" (last (:cmd r)))))))

    (testing "passes build, pipeline and step ids for output capturing"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              r (mcc/run-container
                 {:containers {:type :podman}
                  :build {:build-id "test-build"}
                  :work-dir dir
                  :step {:name "test-step"
                         :index 0
                         :container/image "test-img"
                         :script ["first" "second"]}
                  :pipeline {:name "test-pipeline"}
                  :logging {:maker (fn [_ path]
                                     (swap! log-paths conj path)
                                     (l/->InheritLogger))}})]
          (is (= 2 (count @log-paths)))
          (is (= ["test-build" "test-pipeline" "0"] (->> @log-paths
                                                         first
                                                         (take 3)))))))

    (testing "uses dummy build id when none given"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              r (mcc/run-container
                 {:containers {:type :podman}
                  :work-dir dir
                  :step {:name "test-step"
                         :index 0
                         :container/image "test-img"
                         :script ["first" "second"]}
                  :pipeline {:name "test-pipeline"}
                  :logging {:maker (fn [_ path]
                                     (swap! log-paths conj path)
                                     (l/->InheritLogger))}})]
          (is (= "no-build-id" (->> @log-paths
                                    ffirst))))))))

(defn- contains-subseq? [l expected]
  (let [n (count expected)]
    (loop [t l]
      (if (= (take n t) expected)
        true
        (if (< (count t) n)
          false
          (recur (rest t)))))))

(deftest build-cmd-args
  (let [base-ctx {:build-id "test-build"
                  :work-dir "test-dir"
                  :step {:name "test-step"
                         :container/image "test-img"
                         :script ["first" "second"]}}]
    
    (testing "when no command given, assumes `/bin/sh` and fails on errors"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (= "-ec" (last (drop-last r))))))
    
    (testing "cmd overrides sh command"
      (let [r (-> base-ctx
                  (assoc-in [:step :container/cmd] ["test-entry"])
                  sut/build-cmd-args)]
        (is (= "test-entry" (last (drop-last r))))))

    (testing "adds all script entries as a single arg"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (= "first && second" (last r)))))

    (testing "mounts"
      
      (testing "adds mounts to args"
        (let [r (sut/build-cmd-args (assoc-in base-ctx
                                              [:step :container/mounts] [["/host/path" "/container/path"]]))]
          (is (contains-subseq? r ["-v" "/host/path:/container/path"])))))

    (testing "adds env vars"
      (let [r (-> base-ctx
                  (assoc-in [:step :container/env] {"VAR1" "value1"
                                                    "VAR2" "value2"})
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["-e" "VAR1=value1"]))
        (is (contains-subseq? r ["-e" "VAR2=value2"]))))

    (testing "passes entrypoint as json if specified"
      (let [r (-> base-ctx
                  (assoc-in [:step :container/entrypoint] ["test-ep"])
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["--entrypoint" "'[\"test-ep\"]'"]))))
    
    (testing "overrides entrypoint for script"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (contains-subseq? r ["--entrypoint" "/bin/sh"]))))

    (testing "adds platform if specified"
      (let [r (-> base-ctx
                  (assoc-in [:step :container/platform] "linux/arm64")
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["--platform" "linux/arm64"]))))

    (testing "adds default platform from app config"
      (is (contains-subseq? (sut/build-cmd-args {:container {:platform "test-platform"}})
                            ["--platform" "test-platform"])))))
