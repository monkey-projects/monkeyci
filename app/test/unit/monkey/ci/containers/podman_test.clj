(ns monkey.ci.containers.podman-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as pi]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.test.helpers :as h :refer [contains-subseq?]]))

(deftest build-cmd-args
  (h/with-tmp-dir dir
    (let [wd (str dir "/work")
          sd (str dir "/script")
          job {:id "test-job"
               :container/image "test-img"
               :script ["first" "second"]}
          base-conf {:job job
                     :sid ["test-build" (:id job)]
                     :work-dir wd
                     :script-dir sd}]
      
      (testing "with script"
        (let [r (-> base-conf
                    (assoc :log-dir (str dir "/logs"))
                    (sut/build-cmd-args))]
          (testing "mounts script volume"
            (is (contains-subseq? r ["-v" (str dir "/script:/opt/monkeyci/script:Z")])))

          (testing "mounts logs volume"
            (is (contains-subseq? r ["-v" (str dir "/logs:/opt/monkeyci/logs:Z")])))
          
          (testing "generates file per script line"
            (is (fs/exists? sd))
            (doseq [n (range (count (:script job)))]
              (is (fs/exists? (fs/path sd (str n))))))

          (testing "writes job script to script dir"
            (is (fs/exists? (fs/path sd "job.sh"))))

          (testing "invokes job script"
            (is (= ["/opt/monkeyci/script/job.sh" "0" "1"]
                   (take-last 3 r))))

          (testing "sets sidecar env vars"
            (is (contains-subseq? r ["-e" "MONKEYCI_SCRIPT_DIR=/opt/monkeyci/script"]))
            (is (contains-subseq? r ["-e" "MONKEYCI_LOG_DIR=/opt/monkeyci/logs"]))
            (is (contains-subseq? r ["-e" "MONKEYCI_WORK_DIR=/home/monkeyci"]))
            (is (contains-subseq? r ["-e" "MONKEYCI_START_FILE=/opt/monkeyci/script/start"]))
            (is (contains-subseq? r ["-e" "MONKEYCI_ABORT_FILE=/opt/monkeyci/script/abort"]))
            (is (contains-subseq? r ["-e" "MONKEYCI_EVENT_FILE=/opt/monkeyci/script/events.edn"])))

          (testing "creates start file initially"
            (is (fs/exists? (fs/path dir "script/start"))))))
      
      (testing "cmd overrides sh command"
        (let [r (sut/build-cmd-args (assoc-in base-conf [:job :container/cmd] ["test-entry"]))]
          (is (= "test-entry" (last r)))))

      (testing "image"
        (testing "adds when namespace is `:container`"
          (is (contains-subseq? (sut/build-cmd-args base-conf)
                                ["test-img"])))

        (testing "adds when keyword is `:image`"
          (is (contains-subseq? (-> base-conf
                                    (assoc :job (-> job
                                                    (dissoc :container/image)
                                                    (assoc :image "test-img")))
                                    (sut/build-cmd-args))
                                ["test-img"]))))

      (testing "mounts"
        (testing "adds shared mounts to args"
          (let [r (-> base-conf
                      (assoc-in [:job :container/mounts] [["/host/path" "/container/path"]])
                      (sut/build-cmd-args))]
            (is (contains-subseq? r ["-v" "/host/path:/container/path"])))))

      (testing "adds env vars"
        (let [r (-> base-conf
                    (assoc-in [:job :container/env] {"VAR1" "value1"
                                                     "VAR2" "value2"})
                    (sut/build-cmd-args))]
          (is (contains-subseq? r ["-e" "VAR1=value1"]))
          (is (contains-subseq? r ["-e" "VAR2=value2"]))))

      (testing "passes entrypoint as json if specified"
        (let [r (-> base-conf
                    (assoc-in [:job :container/entrypoint] ["test-ep"])
                    (sut/build-cmd-args))]
          (is (contains-subseq? r ["--entrypoint" "'[\"test-ep\"]'"]))))
      
      (testing "overrides entrypoint for script"
        (let [r (sut/build-cmd-args base-conf)]
          (is (contains-subseq? r ["--entrypoint" "/bin/sh"]))))

      (testing "adds arch if specified"
        (let [r (-> base-conf 
                    (assoc-in [:job :arch] :arm)
                    (sut/build-cmd-args))]
          (is (contains-subseq? r ["--arch" "arm64"]))))

      (testing "adds default arch from app config"
        (is (contains-subseq? (sut/build-cmd-args (assoc base-conf :arch :arm))
                              ["--arch" "arm64"])))

      (testing "uses build and job id as container name"
        (is (contains-subseq? (sut/build-cmd-args base-conf)
                              ["--name" "test-build-test-job"])))

      (testing "makes job work dir relative to cmd work dir"
        (let [cmd (-> base-conf
                      (assoc-in [:job :work-dir] "sub-dir")
                      (sut/build-cmd-args))]
          (is (contains-subseq? cmd ["-v" (str wd ":/home/monkeyci:Z")]))
          (is (contains-subseq? cmd ["-w" "/home/monkeyci/sub-dir"])))))))

(deftest job-work-dir
  (testing "returns context work dir"
    (is (= "test-wd/work"
           (-> {}
               (sut/set-job-dir "test-wd")
               (sut/job-work-dir {})))))

  (testing "combines job work dir with context wd"
    (is (= "/test/wd/work/job-dir"
           (-> {}
               (sut/set-job-dir "/test/wd")
               (sut/job-work-dir {:work-dir "job-dir"}))))))

(deftest make-routes
  (let [routes (sut/make-routes {:work-dir "/tmp"})
        expected [:container/job-queued
                  :job/initializing
                  :podman/job-executed]]
    (doseq [t expected]
      (testing (format "handles `%s`" t)
        (is (contains? (set (map first routes)) t))))))

(deftest add-job-dir
  (let [{:keys [enter] :as i} (sut/add-job-dir "/test/dir")]
    (is (keyword? (:name i)))

    (testing "`enter` sets calculated work dir in ctx"
      (is (= "/test/dir/test-cust/test-repo/test-build/test-job"
             (-> {:event
                  {:sid ["test-cust" "test-repo" "test-build"]
                   :job-id "test-job"}}
                 (enter)
                 (sut/get-job-dir)))))))

(deftest restore-ws
  (h/with-tmp-dir dir
    (let [ws (h/fake-blob-store (atom {"test-build.tgz" "test-dest"}))
          wd (fs/create-dir (fs/path dir "workdir"))
          {:keys [enter] :as i} (sut/restore-ws ws)]
      (is (keyword? (:name i)))

      (testing "`enter`"
        (let [r (-> {:event
                     {:sid ["test-build"]
                      :job-id "test-job"}}
                    (sut/set-job-dir wd)
                    (enter))]
          (testing "adds workspace to context"
            (is (some? (::sut/workspace r))))

          (testing "copies files from workspace to job work dir"
            (is (empty? @(:stored ws)))))))))

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
        {:keys [enter] :as i} (sut/add-job-ctx {:checkout-dir "/orig/dir"})]
    (is (keyword? (:name i)))

    (testing "`enter`"
      (let [r (-> {:event
                   {:sid ["test-build"]
                    :job-id (:id job)}}
                  (sut/set-job job)
                  (sut/set-job-dir "/new/dir")
                  (enter))]
        (testing "adds job context to context"
          (is (some? (emi/get-job-ctx r))))

        (testing "adds job from state to job context"
          (is (= job (:job (emi/get-job-ctx r)))))

        (testing "sets checkout dir to workspace dir"
          (is (= "/new/dir/work"
                 (-> (emi/get-job-ctx r)
                     :checkout-dir))))))))

(deftest cleanup
  (let [i (sut/cleanup {})]
    (is (keyword? (:name i)))

    (testing "`leave`"
      (h/with-tmp-dir dir
        (let [wd (fs/create-dirs dir "work")
              {:keys [leave]} (sut/cleanup {:cleanup? false})]
          (testing "does nothing if `cleanup?` is `false`"
            (is (some? (-> {}
                           (sut/set-job-dir wd)
                           (leave))))
            (is (fs/exists? wd)))

          (testing "deletes job dir if `cleanup?` is `true`"
            (let [{:keys [leave]} (sut/cleanup {:cleanup? true})]
              (is (some? (-> {}
                             (sut/set-job-dir wd)
                             (leave))))
              (is (not (fs/exists? wd))))))))))

(deftest job-queued
  (testing "returns `job/initializing` event"
    (is (= [:job/initializing]
           (->> {:event
                 {:type :job/queued
                  :job-id "test-job"}}
                (sut/job-queued {})
                (map :type)))))

  (testing "adds credit multiplier from config"
    (is (= ::cm
           (-> {:credit-multiplier ::cm}
               (sut/job-queued {:event {:type :job/queued}})
               first
               :credit-multiplier)))))

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
               (sut/prepare-child-cmd)
               :cmd
               first)))))
