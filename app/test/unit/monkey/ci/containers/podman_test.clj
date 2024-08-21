(ns monkey.ci.containers.podman-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.process :as bp]
            [monkey.ci
             [containers :as mcc]
             [logging :as l]]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.helpers :as h :refer [contains-subseq?]]))

(deftest run-container
  (with-redefs [bp/process (fn [args]
                             (future args))]
    
    (testing "starts podman process"   
      (h/with-tmp-dir dir
        (let [r @(mcc/run-container
                  {:containers {:type :podman}
                   :build {:build-id "test-build"}
                   :work-dir dir
                   :logging {:maker (l/make-logger {})}
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]}})]
          (is (map? r))
          (is (= "/usr/bin/podman" (-> r :cmd first)))
          (is (contains? (set (:cmd r))
                         "test-img"))
          (is (= "first && second" (last (:cmd r)))))))

    (testing "passes build and job ids for output capturing"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              r @(mcc/run-container
                  {:containers {:type :podman}
                   :build {:build-id "test-build"}
                   :work-dir dir
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]}
                   :logging {:maker (fn [_ path]
                                      (swap! log-paths conj path)
                                      (l/->InheritLogger))}})]
          (is (= 2 (count @log-paths)))
          (is (= ["test-build" "test-job"] (->> @log-paths
                                                first
                                                (take 2)))))))

    (testing "uses dummy build id when none given"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              r @(mcc/run-container
                  {:containers {:type :podman}
                   :work-dir dir
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]}
                   :pipeline {:name "test-pipeline"}
                   :logging {:maker (fn [_ path]
                                      (swap! log-paths conj path)
                                      (l/->InheritLogger))}})]
          (is (= "unknown-build" (->> @log-paths
                                      ffirst))))))

    (testing "restores and saves caches if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              cache (h/fake-blob-store stored)
              r @(mcc/run-container
                  {:containers {:type :podman}
                   :build {:build-id "test-build"}
                   :work-dir dir
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :caches [{:id "test-cache"
                                   :path "test-path"}]}
                   :logging {:maker (l/make-logger {})}
                   :cache cache})]
          (is (some? r))
          (is (not-empty @stored)))))
    
    (testing "restores artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {"test-cust/test-build/test-artifact.tgz" ::test})
              store (h/fake-blob-store stored)
              r @(mcc/run-container
                  {:containers {:type :podman}
                   :build {:build-id "test-build"
                           :sid ["test-cust" "test-build"]}
                   :work-dir dir
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :restore-artifacts [{:id "test-artifact"
                                              :path "test-path"}]}
                   :logging {:maker (l/make-logger {})}
                   :artifacts store})]
          (is (some? r))
          (is (empty? @stored)))))

    (testing "saves artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              store (h/fake-blob-store stored)
              r @(mcc/run-container
                  {:containers {:type :podman}
                   :build {:build-id "test-build"
                           :sid ["test-cust" "test-build"]}
                   :work-dir dir
                   :job {:id "test-job"
                         :container/image "test-img"
                         :script ["first" "second"]
                         :save-artifacts [{:id "test-artifact"
                                           :path "test-path"}]}
                   :logging {:maker (l/make-logger {})}
                   :artifacts store})]
          (is (some? r))
          (is (not-empty @stored)))))))

(deftest build-cmd-args
  (let [base-ctx {:build {:build-id "test-build"
                          :checkout-dir "/test-dir/checkout"}
                  :work-dir "test-dir"
                  :job {:id "test-job"
                        :container/image "test-img"
                        :script ["first" "second"]}}]
    
    (testing "when no command given, assumes `/bin/sh` and fails on errors"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (= "-ec" (last (drop-last r))))))
    
    (testing "cmd overrides sh command"
      (let [r (-> base-ctx
                  (assoc-in [:job :container/cmd] ["test-entry"])
                  sut/build-cmd-args)]
        (is (= "test-entry" (last (drop-last r))))))

    (testing "adds all script entries as a single arg"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (= "first && second" (last r)))))

    (testing "image"
      (testing "adds when namespace is `:container`"
        (is (contains-subseq? (sut/build-cmd-args base-ctx)
                              ["test-img"])))

      (testing "adds when keyword is `:image`"
        (is (contains-subseq? (-> base-ctx
                                  (update :job dissoc :container/image)
                                  (update :job assoc :image "test-img")
                                  (sut/build-cmd-args))
                              ["test-img"]))))

    (testing "mounts"
      
      (testing "adds shared mounts to args"
        (let [r (sut/build-cmd-args (assoc-in base-ctx
                                              [:job :container/mounts] [["/host/path" "/container/path"]]))]
          (is (contains-subseq? r ["-v" "/host/path:/container/path"])))))

    (testing "adds env vars"
      (let [r (-> base-ctx
                  (assoc-in [:job :container/env] {"VAR1" "value1"
                                                   "VAR2" "value2"})
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["-e" "VAR1=value1"]))
        (is (contains-subseq? r ["-e" "VAR2=value2"]))))

    (testing "passes entrypoint as json if specified"
      (let [r (-> base-ctx
                  (assoc-in [:job :container/entrypoint] ["test-ep"])
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["--entrypoint" "'[\"test-ep\"]'"]))))
    
    (testing "overrides entrypoint for script"
      (let [r (sut/build-cmd-args base-ctx)]
        (is (contains-subseq? r ["--entrypoint" "/bin/sh"]))))

    (testing "adds platform if specified"
      (let [r (-> base-ctx
                  (assoc-in [:job :container/platform] "linux/arm64")
                  (sut/build-cmd-args))]
        (is (contains-subseq? r ["--platform" "linux/arm64"]))))

    (testing "adds default platform from app config"
      (is (contains-subseq? (sut/build-cmd-args {:containers {:platform "test-platform"}})
                            ["--platform" "test-platform"])))

    (testing "uses build and job id as container name"
      (is (contains-subseq? (sut/build-cmd-args base-ctx)
                            ["--name" "test-build-test-job"])))

    (testing "makes job work dir relative to build checkout dir"
      (is (contains-subseq? (-> base-ctx
                                (assoc-in [:job :work-dir] "sub-dir")
                                (sut/build-cmd-args))
                            ["-v" "/test-dir/checkout/sub-dir:/home/monkeyci:Z"])))))
