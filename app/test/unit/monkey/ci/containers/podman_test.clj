(ns monkey.ci.containers.podman-test
  (:require [babashka.fs :as fs]
            [buddy.core.codecs :as bcc]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as pi]
            [manifold.deferred :as md]
            [monkey.ci
             [cuid :as cuid]
             [vault :as v]]
            [monkey.ci.containers.podman :as sut]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.test.helpers :as h :refer [contains-subseq?]]
            [monkey.ci.vault.common :as vc]
            [monkey.mailman.core :as mmc]))

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

      (testing "adds env vars without values"
        (let [r (-> base-conf
                    (assoc-in [:job :container/env] {"VAR1" "value1"
                                                     "VAR2" "value2"})
                    (sut/build-cmd-args))]
          (is (contains-subseq? r ["-e" "VAR1"]))
          (is (contains-subseq? r ["-e" "VAR2"]))))

      (testing "passes reserved env vars explicitly"
        (let [r (-> base-conf
                    (assoc-in [:job :container/env] {"DOCKER_CONFIG" "config-value"})
                    (sut/build-cmd-args))]
          (is (contains-subseq? r ["-e" "DOCKER_CONFIG=config-value"]))))

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

      (testing "resources"
        (testing "applies default memory and cpu limits"
          (let [args (sut/build-cmd-args base-conf)]
            (is (contains-subseq? args ["--cpus" "1"]))
            (is (contains-subseq? args ["--memory" "2g"]))))

        (testing "applies configured memory and cpu limits"
          (let [args (-> base-conf
                         (update :job assoc :memory 3 :cpus 2 )
                         (sut/build-cmd-args))]
            (is (contains-subseq? args ["--cpus" "2"]))
            (is (contains-subseq? args ["--memory" "3g"]))))

        (testing "applies size to memory and cpu limits"
          (let [args (-> base-conf
                         (assoc-in [:job :size] 2)
                         (sut/build-cmd-args))]
            (is (contains-subseq? args ["--cpus" "2"]))
            (is (contains-subseq? args ["--memory" "4g"])))))

      (testing "uses build and job id as container name"
        (is (contains-subseq? (sut/build-cmd-args base-conf)
                              ["--name" "test-build-test-job"])))

      (testing "makes job work dir relative to cmd work dir"
        (let [cmd (-> base-conf
                      (assoc-in [:job :work-dir] "sub-dir")
                      (sut/build-cmd-args))]
          (is (contains-subseq? cmd ["-v" (str wd ":/home/monkeyci:Z")]))
          (is (contains-subseq? cmd ["-w" "/home/monkeyci/sub-dir"]))))

      (testing "allows overriding podman cmd from opts"
        (is (= "/usr/local/bin/podman"
               (-> base-conf
                   (assoc :podman-cmd "/usr/local/bin/podman")
                   (sut/build-cmd-args)
                   first)))))))

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
  (h/with-tmp-dir dir
    (let [state (atom nil)
          routes (sut/make-routes {:work-dir dir :state state})
          expected [:container/job-queued
                    :job/initializing
                    :container/end]]
      (doseq [t expected]
        (testing (format "handles `%s`" t)
          (is (contains? (set (map first routes)) t))))

      (let [fake-proc {:name ::emi/start-process
                       :leave identity}
            router (-> routes
                       (mmc/router)
                       (mmc/replace-interceptors [fake-proc]))
            sid ["test" "build"]
            job-id "test-job"]

        (testing "`container/job-queued`"
          (testing "results in `job/initializing`"
            (let [res (router
                       {:type :container/job-queued
                        :sid sid
                        :job-id job-id
                        :job {:id job-id}})]
              (is (= [:job/initializing]
                     (->> res
                          (mapcat :result)
                          (map :type))))))

          (testing "increases job count in state"
            (is (= 1 (:job-count @state)))))

        (testing "`container/end`"
          (testing "ignores unknown jobs"
            (is (empty? (->> (router {:type :container/end
                                      :sid sid
                                      :job-id "unknown"})
                             (mapcat :result)))))
          
          (let [res (router
                     {:type :container/end
                      :sid sid
                      :job-id job-id})]
            (testing "results in job-executed event"
              (is (= [:job/executed]
                     (->> res
                          (mapcat :result)
                          (map :type)))))
            
            (testing "decreases job count"
              (is (zero? (:job-count @state))))

            (testing "removes job from state"
              (is (empty? (:jobs @state)))))

          (testing "when error, still decreases job count"
            (let [job-id "failing-job"
                  failing-art {:name :monkey.ci.artifacts/save-artifacts
                               :leave (fn [_]
                                        (throw (ex-info "Test error" {})))}
                  router (-> routes
                             (mmc/router)
                             (mmc/replace-interceptors [fake-proc failing-art]))]
              (is (some? (swap! state update :job-count inc)))
              (is (some? (swap! state update :jobs assoc-in [sid job-id] {:id job-id})))
              (is (some? (router {:type :container/end
                                  :sid sid
                                  :job-id job-id})))
              (is (zero? (:job-count @state))))))))))

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

(deftest remove-job
  (let [{:keys [leave] :as i} sut/remove-job
        sid [::test-cust ::test-repo ::test-build]]
    (is (keyword? (:name i)))

    (testing "`leave` removes job from state"
      (let [ctx (-> {:event
                     {:type :job/executed
                      :sid sid
                      :job-id ::test-job}}
                    (emi/set-state {:jobs
                                    {sid
                                     {::test-job ::test-job}}}))]
        (is (= ::test-job (sut/get-job ctx)))
        (let [res (leave ctx)]
          (is (nil? (sut/get-job res)))
          (is (nil? (:jobs (emi/get-state res)))
              "removes sid when no more jobs remain"))))))

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

(deftest inc-job-count
  (let [{:keys [leave] :as i} sut/inc-job-count]
    (is (keyword? (:name i)))
    
    (testing "`leave`"
      (testing "increases job count on empty state"
        (is (= 1 (-> {}
                     (emi/set-state {})
                     (leave)
                     (emi/get-state)
                     (sut/count-jobs)))))

      (testing "increases job count on existing state"
        (is (= 3 (-> {}
                     (emi/set-state {:job-count 2})
                     (leave)
                     (emi/get-state)
                     (sut/count-jobs))))))))

(deftest dec-job-count
  (let [{:keys [leave] :as i} sut/dec-job-count]
    (is (keyword? (:name i)))
    
    (testing "`leave`"
      (testing "decreases job count"
        (is (= 1 (-> {}
                     (emi/set-state {:job-count 2})
                     (leave)
                     (emi/get-state)
                     (sut/count-jobs)))))

      (testing "does not go below zero"
        (is (= 0 (-> {}
                     (emi/set-state {})
                     (leave)
                     (emi/get-state)
                     (sut/count-jobs))))))))

(deftest decrypt-env
  (let [{:keys [enter] :as i} sut/decrypt-env]
    (is (keyword? (:name i)))

    (testing "`enter` decrypts job env vars"
      (let [[org-id :as sid] (repeatedly 3 cuid/random-cuid)
            dek (v/generate-key)
            v "test-secret"
            enc-val (vc/encrypt dek (v/cuid->iv org-id) v)]
        (is (= v
               (-> {:event
                    {:sid sid
                     :dek "encrypted-dek"
                     :job
                     {:container/env
                      {"test-env" enc-val}}}}
                   (sut/set-key-decrypter (fn [k build-sid]
                                            (when (and (= sid build-sid)
                                                       (= "encrypted-dek" k))
                                              (md/success-deferred
                                               (bcc/bytes->b64-str dek)))))
                   (enter)
                   (get-in [:event :job :container/env "test-env"]))))))))

(deftest add-podman-opts
  (let [opts {:podman-cmd "/test/cmd"}
        {:keys [enter] :as i} (sut/add-podman-opts opts)]
    (is (keyword? (:name i)))
    
    (testing "`enter` adds podman options to context"
      (is (= opts
             (-> {}
                 (enter)
                 (sut/podman-opts)))))))

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
               :credit-multiplier))))

  (testing "adds local work dir"
    (let [job-dir "/tmp/job/dir"]
      (is (= job-dir
             (-> {}
                 (sut/job-queued (sut/set-job-dir {} job-dir))
                 first
                 :local-dir))))))

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
                {:type :container/end
                 :job-id "test-job"}}
               (sut/job-exec)
               first)]
      (is (= :job/executed
             (:type r))))))

(deftest prepare-child-cmd
  (testing "executes podman"
    (is (= "podman"
           (-> {:event
                {:job {:id "test-job"
                       :image "test-image"}
                 :job-id "test-job"
                 :sid ["test" "build"]}}
               (sut/prepare-child-cmd)
               :cmd
               first))))

  (testing "allows overriding podman cmd"
    (is (= "/usr/local/bin/podman"
           (-> {:event
                {:job {:id "test-job"
                       :image "test-image"}
                 :job-id "test-job"
                 :sid ["test" "build"]}}
               (sut/set-podman-opts {:podman-cmd "/usr/local/bin/podman"})
               (sut/prepare-child-cmd)
               :cmd
               first))))

  (testing "passes job env"
    (is (= {"test-key" "test-val"}
           (-> {:event
                {:job {:id "env-job"
                       :image "debian"
                       :container/env {"test-key" "test-val"}}}}
               (sut/prepare-child-cmd)
               :extra-env))))

  (testing "does not pass special podman env vars"
    (is (empty?
         (-> {:event
              {:job {:id "env-job"
                     :image "debian"
                     :container/env {"DOCKER_CONFIG" "test-val"}}}}
             (sut/prepare-child-cmd)
             :extra-env)))))

(deftest count-jobs
  (testing "zero when state is empty"
    (is (zero? (sut/count-jobs {}))))

  (testing "returns job count"
    (is (= 3 (sut/count-jobs {:job-count 3})))))
