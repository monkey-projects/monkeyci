(ns monkey.ci.containers.podman-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [babashka
             [fs :as fs]
             [process :as bp]]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as pi]
            [monkey.ci
             [artifacts :as art]
             [cache :as ca]
             [containers :as mcc]
             [logging :as l]
             [protocols :as p]]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h :refer [contains-subseq?]]
            [monkey.ci.test.runtime :as trt]))

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
      (let [cmd (-> job
                    (assoc :work-dir "sub-dir")
                    (sut/build-cmd-args base-conf))]
        (is (contains-subseq? cmd ["-v" "/test-dir/checkout:/home/monkeyci:Z"]))
        (is (contains-subseq? cmd ["-w" "/home/monkeyci/sub-dir"]))))))

(deftest job-work-dir
  (testing "returns context work dir"
    (is (= "test-wd"
           (-> {}
               (sut/set-work-dir "test-wd")
               (sut/job-work-dir {})))))

  (testing "combines job work dir with context wd"
    (is (= "/test/wd/job-dir"
           (-> {}
               (sut/set-work-dir "/test/wd")
               (sut/job-work-dir {:work-dir "job-dir"}))))))

(deftest make-routes
  (let [routes (sut/make-routes {})
        expected [:container/job-queued
                  :job/initializing
                  :podman/job-executed]]
    (doseq [t expected]
      (testing (format "handles `%s`" t)
        (is (contains? (set (map first routes)) t))))))

(deftest copy-ws
  (h/with-tmp-dir dir
    (let [ws (fs/create-dir (fs/path dir "workspace"))
          wd (fs/create-dir (fs/path dir "workdir"))
          {:keys [enter] :as i} (sut/copy-ws ws wd)]
      (is (keyword? (:name i)))

      (is (nil? (spit (fs/file (fs/path ws "test.txt")) "test file")))

      (testing "`enter`"
        (let [r (-> {:event
                      {:job-id "test-job"}}
                     (enter))]
          (testing "adds work dir to context"
            (is (= (fs/path wd "test-job/work")
                   (sut/get-work-dir r))))

          (testing "adds log dir to context"
            (is (= (fs/path wd "test-job/logs")
                   (sut/get-log-dir r))))
          
          (testing "copies files from workspace to job work dir"
            (let [p (fs/path wd "test-job/work/test.txt")]
              (is (fs/exists? p))
              (is (= "test file" (slurp (fs/file p)))))))))))

(deftest filter-container-job
  (let [{:keys [enter] :as i} sut/filter-container-job
        ctx (-> {:event {:job {:id "action-job"}}}
                (pi/enqueue [(i/interceptor {:name ::test-interceptor
                                             :enter identity})]))]
    (is (keyword? (:name i)))
    
    (testing "terminates if no container job"
      (is (nil? (-> ctx
                    (enter)
                    ::pi/queue))))

    (testing "continues if container job"
      (is (some? (-> ctx
                     (assoc-in [:event :job :image] "test-img")
                     (enter)
                     ::pi/queue))))))

(deftest save-job
  (let [{:keys [enter] :as i} sut/save-job]
    (is (keyword? (:name i)))

    (testing "adds event job to state"
      (let [job (h/gen-job)]
        (is (= job (-> {:event
                        {:job job}}
                       (enter)
                       (sut/get-job (:id job)))))))))

(deftest require-job
  (let [{:keys [enter] :as i} sut/require-job
        ctx (-> {}
                (pi/enqueue [(i/interceptor {:name ::test-interceptor
                                             :enter identity})]))] 
    (is (keyword? (:name i)))
    
    (testing "terminates if no job in state"
      (is (nil? (-> ctx
                    (enter)
                    ::pi/queue))))

    (testing "continues if job in state"
      (let [job (h/gen-job)]
        (is (some? (-> {:event {:job-id (:id job)}}
                       (sut/set-job job)
                       (pi/enqueue [(i/interceptor {:name ::test-interceptor
                                                    :enter identity})])
                       (enter)
                       ::pi/queue)))))))

(deftest add-job-ctx
  (let [job {:id "test-job"}
        {:keys [enter] :as i} (sut/add-job-ctx {:build {:checkout-dir "/orig/dir"}})]
    (is (keyword? (:name i)))

    (testing "`enter`"
      (let [r (-> {:event
                   {:job-id (:id job)}}
                  (sut/set-job job)
                  (sut/set-work-dir "/new/dir")
                  (enter))]
        (testing "adds job context to context"
          (is (some? (emi/get-job-ctx r))))

        (testing "adds job from state to job context"
          (is (= job (:job (emi/get-job-ctx r)))))

        (testing "sets build checkout dir to workspace dir"
          (is (= "/new/dir" (-> (emi/get-job-ctx r)
                                :build
                                :checkout-dir))))))))

(deftest job-queued
  (testing "returns `job/initializing` event"
    (is (= [:job/initializing]
           (->> {:event
                 {:type :job/queued
                  :job-id "test-job"}}
                (sut/job-queued)
                (map :type))))))

(deftest job-init
  (testing "returns `job/start` event"
    (is (= :job/start
           (-> {:event
                {:type :job/initializing
                 :job-id "test-job"}}
               (sut/job-init)
               first
               :type)))))

(deftest job-exec
  (testing "returns `job/executed` event"
    (let [r (-> {:event
                {:type :podman/job-executed
                 :job-id "test-job"}}
               (sut/job-exec)
               first)]
      (is (= :job/executed
             (:type r))))))

(deftest prepare-child-cmd
  (testing "executes podman"
    (is (= "/usr/bin/podman"
           (-> {:job {:id "test-job"
                      :image "test-image"}
                :job-id "test-job"
                :sid ["test" "build"]}
               (sut/set-build (h/gen-build))
               (sut/prepare-child-cmd)
               :cmd
               first)))))
