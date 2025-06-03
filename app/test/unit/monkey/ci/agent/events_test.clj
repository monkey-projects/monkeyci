(ns monkey.ci.agent.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [clojure.string :as cs]
            [babashka.fs :as fs]
            [io.pedestal.interceptor :as i]
            [io.pedestal.interceptor.chain :as ic]
            [monkey.ci.agent.events :as sut]
            [monkey.ci
             [build :as b]
             [edn :as edn]]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]
            [monkey.ci.spec.script :as ss]
            [monkey.ci.test
             [helpers :as h]
             [mailman :as tm]]
            [monkey.mailman
             [core :as mmc]
             [mem :as mem]
             [manifold :as mmm]]))

(deftest routes
  (let [evts [:build/queued :build/end :script/initializing]
        routes (sut/make-routes {})]
    (doseq [t evts]
      (testing (str "handles " t)
        (is (contains? (->> routes
                            (map first)
                            (set))
                       t)))))

  (h/with-tmp-dir dir
    (let [build (h/gen-build)
          procs (atom [])
          builds (atom {})
          fake-proc {:name (:name emi/start-process)
                     :leave (fn [ctx]
                              (swap! procs conj (:result ctx))
                              ctx)}
          ws (h/fake-blob-store)
          router (-> {:git {:clone (constantly "test-checkout")}
                      :workspace ws
                      :builds builds
                      :work-dir dir
                      :ssh-keys-fetcher (constantly nil)}
                     (sut/make-routes)
                     (mmc/router)
                     (mmc/replace-interceptors [fake-proc]))]
      (testing "`build/queued`"
        (is (= [:build/initializing]
               (->> (router {:type :build/queued
                             :sid (b/sid build)
                             :build build})
                    first
                    :result
                    (map :type)))
            "fires build/initializing")

        (testing "starts build container"
          (is (= 1 (count @procs))))

        (testing "adds token to builds"
          (is (= 1 (count @builds))))

        (testing "saves workspace"
          (is (= {(str (cs/join "/" (b/sid build)) ".tgz") "test-checkout"} @(:stored ws)))))

      (testing "`script/initializing`"
        (testing "fires `build/start`"
          (is (= [:build/start]
                 (->> (router {:type :script/initializing
                               :sid (b/sid build)})
                      first
                      :result
                      (map :type))))))

      (testing "`build/end`"
        (testing "removes token from builds"
          (is (some? (router {:type :build/end
                              :sid (b/sid build)})))
          (is (empty? @builds)))))))

(deftest add-config
  (let [{:keys [enter] :as i} (sut/add-config ::config)]
    (is (keyword? (:name i)))
    
    (testing "`enter` sets config in context"
      (is (= ::config (-> (enter {})
                          (sut/get-config)))))))

(defn- random-sid []
  (repeatedly 3 (comp str random-uuid)))

(deftest fetch-ssh-keys
  (let [{:keys [enter] :as i} (sut/fetch-ssh-keys identity)]
    (is (keyword? (:name i)))

    (testing "`enter` fetches keys for repo sid"
      (let [sid (random-sid)]
        (is (= sid
               (-> {:event {:sid sid}}
                   (enter)
                   (sut/get-ssh-keys))))))))

(deftest git-clone
  (let [{:keys [enter] :as i} sut/git-clone]
    (is (keyword? (:name i)))
    
    (testing "`enter`"      
      (let [args (atom nil)
            clone (partial reset! args)]
        (is (some? (-> {:event
                        {:build {:git {:ssh-keys [{:private-key "old-pk"}]}}}}
                       (sut/set-config {:work-dir "/tmp/test-dir"
                                        :git {:clone clone}})
                       (sut/set-ssh-keys ["fetched-pk"])
                       (enter))))

        (testing "invokes git clone fn with build git details"
          (is (= "/tmp/test-dir/checkout"
                 (:dir @args))))

        (testing "passes fetched ssh keys"
          (is (= [{:private-key "fetched-pk"}] (:ssh-keys @args))))

        (testing "configures `ssh-keys-dir`"
          (is (string? (:ssh-keys-dir @args))))))))

(deftest prepare-build-cmd
  (h/with-tmp-dir dir
    (let [build (-> (h/gen-build)
                    (dissoc :script))
          broker (tm/test-component)
          cmd (-> {:event
                   {:build build
                    :sid (b/sid build)}}
                  (sut/set-config {:image "test-img"
                                   :work-dir dir
                                   :mailman broker
                                   :api-server
                                   {:port 1234}
                                   :log-config "test-log-config"})
                  (sut/prepare-build-cmd))]
      (testing "executes podman"
        (is (= "podman" (-> cmd :cmd first))))

      (testing "runs clojure cmd"
        (is (contains? (set (:cmd cmd)) "clojure")))

      (testing "uses configured image"
        (is (contains? (set (:cmd cmd)) "test-img")))

      (testing "mounts checkout dir"
        (let [m (->> cmd
                     :cmd
                     (drop-while (partial not= "-v"))
                     (fnext))]
          (is (some? m))
          (is (cs/ends-with? m "/home/monkeyci:Z"))))

      (testing "sets container work dir to script dir"
        (is (= "/home/monkeyci/.monkeyci"
               (->> cmd
                    :cmd
                    (drop-while (partial not= "--workdir"))
                    (fnext)))))

      (testing "sets process work dir to local script dir"
        (is (= (str (apply fs/path dir (conj (b/sid build) "checkout/.monkeyci")))
               (:dir cmd))))

      (testing "runs in host network"
        (is (contains? (set (:cmd cmd)) "--network=host")))

      (testing "deps"
        (let [deps (->> cmd
                        :cmd
                        (drop-while (partial not= "-Sdeps"))
                        (fnext)
                        (edn/edn->))]
          (testing "is passed on cmdline"
            (is (some? deps)))

          (let [args (get-in deps [:aliases :monkeyci/build :exec-args :config])]
            (testing "contains exec args"
              (is (map? args)))
            
            (testing "contains api server url"
              (is (re-matches #"^http://\d+\.\d+.\d+.\d+:\d+$" (:url (sc/api args))))))

          (testing "adds logback configuration"
            (let [args (get-in deps [:aliases :monkeyci/build :jvm-opts])]
              (is (not-empty args))
              (is (re-matches #"^-Dlogback.configurationFile=.+$" (first args)))))))

      (testing "on exit, fires `build/end` event"
        (let [on-exit (:exit-fn cmd)]
          (is (fn? on-exit))
          (is (some? (on-exit {:exit 0})))
          (is (not (= :timeout (h/wait-until #(not-empty (tm/get-posted broker)) 1000))))
          (is (= [:build/end]
                 (->> (tm/get-posted broker)
                      (map :type)))))))))

(deftest generate-deps
  (let [deps (sut/generate-deps "test-dir" "test-version" {})]
    (testing "includes script dir"
      (is (some? (:paths deps))))

    (testing "includes config args"
      (is (some? (get-in deps [:aliases :monkeyci/build :exec-args]))))

    (testing "sets m2 cache path"
      (is (= sut/m2-cache-path (:mvn/local-repo deps))))))

(deftest generate-script-config
  (testing "builds script config"
    (let [conf (-> {:event {:build (h/gen-build)}}
                   (sut/set-config {:api-server {:port 1234}})
                   (sut/set-token "test-token")
                   (sut/generate-script-config))]
      (is (spec/valid? ::ss/config conf)
          (spec/explain-str ::ss/config conf)))))

(deftest script-init
  (let [sid (random-sid)
        r (sut/script-init {:credit-multiplier ::cm}
                           {:event {:sid sid}})]
    (testing "returns `build/start`"
      (is (= :build/start (-> r first :type))))

    (testing "uses correct sid"
      (is (= sid (-> r first :sid))))

    (testing "adds credit multiplier from config"
      (is (= ::cm (-> r first :credit-multiplier))))))

(deftest filter-known-builds
  (let [{:keys [enter] :as i} sut/filter-known-builds]
    (is (keyword? (:name i)))

    (testing "`enter`"
      (let [sid (random-sid)
              builds (atom {"test-token" {:sid sid}})
              ctx (-> {:event
                       {:type :build/end
                        :sid sid}}
                      (sut/set-config {:builds builds})
                      (ic/enqueue [(i/interceptor {:name ::test
                                                   :enter identity})]))]
        (testing "continues when build has known token"
          (is (some? (-> (enter ctx)
                         ::ic/queue))))

        (testing "terminates when build token is unknown"
          (is (empty? (reset! builds {})))
          (is (nil? (-> (enter ctx)
                        ::ic/queue))))))))

(deftest cleanup
  (h/with-tmp-dir dir
    (let [sid (random-sid)
          conf {:work-dir dir
                :cleanup? true}
          wd (sut/build-work-dir conf sid)
          {:keys [leave] :as i} sut/cleanup]
      (is (keyword? (:name i)))

      (is (some? (fs/create-dirs wd)))
      
      (testing "`leave`"
        (let [base-ctx {:event {:sid sid}}]
          (testing "does nothing if not enabled"
            (let [ctx (-> base-ctx
                          (sut/set-config (dissoc conf :cleanup?)))]
              (is (= ctx (leave ctx)))
              (is (fs/exists? wd))))

          (testing "when enabled, deletes all files in work dir"
            (let [ctx (sut/set-config base-ctx conf)]
              (is (= ctx (leave ctx)))
              (is (not (fs/exists? wd))))))))))


