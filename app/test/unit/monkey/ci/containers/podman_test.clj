(ns monkey.ci.containers.podman-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [babashka.process :as bp]
            [monkey.ci
             [artifacts :as art]
             [cache :as ca]
             [containers :as mcc]
             [logging :as l]
             [protocols :as p]]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h :refer [contains-subseq?]]
            [monkey.ci.test.runtime :as trt]))

(defn test-rt [dir]
  {:containers {:type :podman}
   :build {:build-id "test-build"
           :sid (h/gen-build-sid)}
   :work-dir dir
   :logging {:maker (l/make-logger {})}
   :job {:id "test-job"
         :container/image "test-img"
         :script ["first" "second"]}
   :events (h/fake-events)})

(deftest container-runner
  (with-redefs [bp/process (fn [args]
                             (future args))]
    
    (testing "starts podman process"   
      (h/with-tmp-dir dir
        (let [rt (test-rt dir)
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (map? r))
          (is (= "/usr/bin/podman" (-> r :cmd first)))
          (is (contains? (set (:cmd r))
                         "test-img"))
          (is (= "first && second" (last (:cmd r)))))))

    (testing "passes build and job ids for output capturing"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              rt (-> (test-rt dir)
                     (assoc-in [:logging :maker] (fn [_ path]
                                                   (swap! log-paths conj path)
                                                   (l/->InheritLogger))))
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (= 2 (count @log-paths)))
          (is (= ["test-build" "test-job"] (->> @log-paths
                                                first
                                                (take 2)))))))

    (testing "uses dummy build id when none given"
      (h/with-tmp-dir dir
        (let [log-paths (atom [])
              rt (-> (test-rt dir) 
                     (update :build dissoc :build-id)
                     (assoc-in [:logging :maker] (fn [_ path]
                                                   (swap! log-paths conj path)
                                                   (l/->InheritLogger))))
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (= "unknown-build" (->> @log-paths
                                      ffirst))))))

    (testing "restores and saves caches if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              cache (ca/make-blob-repository (h/fake-blob-store stored) {})
              rt (-> (test-rt dir)
                     (assoc-in [:job :caches] [{:id "test-cache"
                                                :path "test-path"}])
                     (trt/set-cache cache))
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (some? r))
          (is (not-empty @stored)))))
    
    (testing "restores artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {"test-cust/test-build/test-artifact.tgz" ::test})
              build {:sid ["test-cust" "test-build"]}
              store (art/make-blob-repository (h/fake-blob-store stored) build)
              rt (-> (test-rt dir)
                     (assoc :build build)
                     (assoc-in [:job :restore-artifacts] [{:id "test-artifact"
                                                           :path "restore-path"}])
                     (trt/set-artifacts store))
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (some? r))
          (is (empty? @stored)))))

    (testing "saves artifacts if configured"
      (h/with-tmp-dir dir
        (let [stored (atom {})
              store (art/make-blob-repository (h/fake-blob-store stored) {})
              rt (-> (test-rt dir)
                     (assoc-in [:job :save-artifacts] [{:id "test-artifact"
                                                        :path "save-path"}])
                     (trt/set-artifacts store))
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))]
          (is (some? r))
          (is (not-empty @stored)))))

    (testing "fires `job/start` event"
      (h/with-tmp-dir dir
        (let [rt (test-rt dir)
              store (art/make-blob-repository (h/fake-blob-store) {})
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))
              evt (->> (h/received-events (:events rt))
                       (h/first-event-by-type :job/start))]
          (is (some? evt))
          (is (spec/valid? ::se/event evt)))))

    (testing "fires `job/end` event"
      (h/with-tmp-dir dir
        (let [rt (test-rt dir)
              store (art/make-blob-repository (h/fake-blob-store) {})
              runner (sut/make-container-runner rt)
              r @(p/run-container runner (:job rt))
              evt (->> (h/received-events (:events rt))
                       (h/first-event-by-type :job/end))]
          (is (some? evt))
          (is (spec/valid? ::se/event evt)))))))

(deftest build-cmd-args
  (let [job {:id "test-job"
             :container/image "test-img"
             :script ["first" "second"]}
        base-conf {:build {:build-id "test-build"
                           :checkout-dir "/test-dir/checkout"}
                   :work-dir "test-dir"}]
    
    (testing "when no command given, assumes `/bin/sh` and fails on errors"
      (let [r (sut/build-cmd-args job base-conf)]
        (is (= "-ec" (last (drop-last r))))))
    
    (testing "cmd overrides sh command"
      (let [r (sut/build-cmd-args (assoc job :container/cmd ["test-entry"]) base-conf)]
        (is (= "test-entry" (last (drop-last r))))))

    (testing "adds all script entries as a single arg"
      (let [r (sut/build-cmd-args job base-conf)]
        (is (= "first && second" (last r)))))

    (testing "image"
      (testing "adds when namespace is `:container`"
        (is (contains-subseq? (sut/build-cmd-args job base-conf)
                              ["test-img"])))

      (testing "adds when keyword is `:image`"
        (is (contains-subseq? (-> job
                                  (dissoc :container/image)
                                  (assoc :image "test-img")
                                  (sut/build-cmd-args base-conf))
                              ["test-img"]))))

    (testing "mounts"
      
      (testing "adds shared mounts to args"
        (let [r (sut/build-cmd-args (assoc job :container/mounts [["/host/path" "/container/path"]])
                                    base-conf)]
          (is (contains-subseq? r ["-v" "/host/path:/container/path"])))))

    (testing "adds env vars"
      (let [r (sut/build-cmd-args
               (assoc job :container/env {"VAR1" "value1"
                                          "VAR2" "value2"})
               base-conf)]
        (is (contains-subseq? r ["-e" "VAR1=value1"]))
        (is (contains-subseq? r ["-e" "VAR2=value2"]))))

    (testing "passes entrypoint as json if specified"
      (let [r (-> job
                  (assoc :container/entrypoint ["test-ep"])
                  (sut/build-cmd-args base-conf))]
        (is (contains-subseq? r ["--entrypoint" "'[\"test-ep\"]'"]))))
    
    (testing "overrides entrypoint for script"
      (let [r (sut/build-cmd-args job base-conf)]
        (is (contains-subseq? r ["--entrypoint" "/bin/sh"]))))

    (testing "adds platform if specified"
      (let [r (-> job 
                  (assoc :container/platform "linux/arm64")
                  (sut/build-cmd-args base-conf))]
        (is (contains-subseq? r ["--platform" "linux/arm64"]))))

    (testing "adds default platform from app config"
      (is (contains-subseq? (sut/build-cmd-args job {:platform "test-platform"})
                            ["--platform" "test-platform"])))

    (testing "uses build and job id as container name"
      (is (contains-subseq? (sut/build-cmd-args job base-conf)
                            ["--name" "test-build-test-job"])))

    (testing "makes job work dir relative to build checkout dir"
      (is (contains-subseq? (-> job
                                (assoc :work-dir "sub-dir")
                                (sut/build-cmd-args base-conf))
                            ["-v" "/test-dir/checkout/sub-dir:/home/monkeyci:Z"])))))
