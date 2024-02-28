(ns monkey.ci.script-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [martian
             [core :as martian]
             [test :as mt]]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [containers :as c]
             [jobs :as j]
             [runtime :as rt]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.web.script-api :as script-api]
            [monkey.ci.build.core :as bc]
            [monkey.ci.helpers :as h]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]
            [org.httpkit.fake :as hf]
            [schema.core :as s]))

(defn with-listening-socket [f]
  (let [p (u/tmp-file "test-" ".sock")]
    (try
      (let [events (atom [])
            server (script-api/listen-at-socket p {:public-api script-api/local-api
                                                   :events {:poster (partial swap! events conj)}})]
        (try
          (f p events)
          (finally
            (script-api/stop-server server))))
      (finally
        (uds/delete-address p)))))

(defn dummy-job
  ([r]
   (bc/action-job ::test-job (constantly r)))
  ([]
   (dummy-job bc/success)))

(deftest resolve-jobs
  (testing "returns jobs from pipeline vector"
    (let [[a b :as jobs] (repeatedly 2 dummy-job)]
      (is (= jobs (sut/resolve-jobs [(bc/pipeline {:jobs [a]})
                                     (bc/pipeline {:jobs [b]})]
                                    {})))))

  (testing "invokes fn"
    (let [job (dummy-job)
          p (bc/pipeline {:jobs [job]})]
      (is (= [job] (sut/resolve-jobs (constantly p) {})))))

  (testing "returns jobs from single pipeline"
    (let [job (dummy-job)
          p (bc/pipeline {:jobs [job]})]
      (is (= [job] (sut/resolve-jobs p {})))))
  
  (testing "makes each job dependent on the previous"
    (let [[a b :as jobs] [{:id ::first
                           :action (constantly ::first)}
                          {:id ::second
                           :action (constantly ::second)}]
          p (sut/resolve-jobs (bc/pipeline {:jobs jobs}) {})]
      (is (= [::first] (-> p second :dependencies)))))

  (testing "auto-assigns ids to jobs"
    (let [jobs (repeat 10 {:action (constantly ::test)})
          p (sut/resolve-jobs (bc/pipeline {:jobs jobs}) {})]
      (is (every? :id p))))

  (testing "assigns id as metadata to function"
    (let [p (sut/resolve-jobs (bc/pipeline {:jobs [(bc/as-job (constantly ::ok))]}) {})]
      (is (= 1 (count p)))
      (is (some? (-> p
                     first
                     bc/job-id)))))

  (testing "does not overwrite existing id"
    (is (= ::test-id (-> {:jobs [{:id ::test-id
                                  :action (constantly :ok)}]}
                         (bc/pipeline)
                         (sut/resolve-jobs {})
                         first
                         bc/job-id))))

  (testing "adds pipeline name as label"
    (is (= "test-pipeline" (-> {:jobs [(dummy-job)]
                                :name "test-pipeline"}
                               (bc/pipeline)
                               (sut/resolve-jobs {})
                               first
                               j/labels
                               (get "pipeline"))))))

(deftest exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"}))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-script"}))))

  (testing "executes dynamic pipelines"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/dynamic-pipelines"}))))

  (testing "skips `nil` pipelines"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/conditional-pipelines"}))))
  
  (testing "fails when invalid script"
    (is (bc/failed? (sut/exec-script! {:script-dir "examples/invalid-script"})))))

(deftest setup-runtime
  (testing "connects to listening socket if specified"
    (with-listening-socket
      (fn [socket-path _]
        (is (some? (-> (rt/setup-runtime {:api {:socket socket-path}} :api)
                       :client)))))))

(deftest make-client
  (testing "creates client for domain socket"
    (is (some? (sut/make-client {:api {:socket "test.sock"}}))))

  (testing "creates client for host"
    (hf/with-fake-http ["http://test/script/swagger.json" 200]
      (is (some? (sut/make-client {:api {:url "http://test"}}))))))

(deftest run-all-jobs
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-all-jobs {} []))))

  (testing "success if all jobs succeed"
    (is (bc/success? (->> [(dummy-job bc/success)]
                          (sut/run-all-jobs {})))))

  (testing "fails if a job fails"
    (is (bc/failed? (->> [(dummy-job bc/failure)]
                         (sut/run-all-jobs {})))))

  (testing "success if job returns `nil`"
    (is (bc/success? (->> [(dummy-job nil)]
                          (sut/run-all-jobs {})))))

  (testing "runs jobs filtered by pipeline name"
    (is (bc/success? (->> [(bc/pipeline {:name "first"
                                         :jobs [(dummy-job bc/success)]})
                           (bc/pipeline {:name "second"
                                         :jobs [(dummy-job bc/failure)]})]
                          (sut/run-all-jobs {:pipeline "first"})))))

  #_(testing "posts events through api"
    (letfn [(verify-evt [expected-type]
              (let [events-posted (atom [])
                    ;; Set up a fake api
                    client (-> (martian/bootstrap "http://test"
                                                  [{:route-name :post-event
                                                    :path-parts ["/event"]
                                                    :method :post
                                                    :body-schema {:event s/Any}}])
                               (mt/respond-with {:post-event (fn [req]
                                                               (swap! events-posted conj (:body req))
                                                               (future {:status 200}))}))
                    jobs [(dummy-job bc/success)]
                    rt {:api {:client client}}]
                (is (bc/success? (sut/run-all-jobs rt jobs)))
                (is (not-empty @events-posted))
                (is (true? (-> (map :type @events-posted)
                               (set)
                               (contains? expected-type))))))]

      ;; Run a test for each type
      (->> [:job/start
            :job/end]
           (map (fn [t]
                  (testing (str t)
                    (verify-evt t))))
           (doall)))))

#_(defmethod c/run-container :test [ctx]
  {:test-result :run-from-test
   :context ctx
   :status :success
   :exit 0})

#_(deftest pipeline-run-job
  (testing "fails on invalid config"
    (is (thrown? Exception (sut/run-job {:job (constantly bc/success)}))))

  (testing "executes action from map"
    (is (bc/success? (sut/run-job {:job {:action (constantly bc/success)}}))))

  (testing "executes in container if configured"
    (let [ctx {:job {:container/image "test-image"}
               :containers {:type :test}}
          r (sut/run-job ctx)]
      (is (= :run-from-test (:test-result r)))
      (is (bc/success? r))))

  (testing "restores/saves cache if configured"
    (let [saved (atom false)]
      (with-redefs [cache/save-caches
                    (fn [ctx]
                      (reset! saved true)
                      ctx)
                    cache/restore-caches
                    (fn [ctx]
                      (->> (get-in ctx [:job :caches])
                           (mapv :id)))]
        (let [ctx {:job {:action (fn [ctx]
                                    (when-not (= [:test-cache] (get-in ctx [:job :caches]))
                                      bc/failure))
                          :caches [{:id :test-cache
                                    :path "test-cache"}]}}
              r (sut/run-job ctx)]
          (is (bc/success? r))
          (is (true? @saved))))))

  (testing "saves artifacts if configured"
    (let [saved (atom false)]
      (with-redefs [art/save-artifacts
                    (fn [ctx]
                      (reset! saved true)
                      ctx)]
        (let [ctx {:job {:action (fn [ctx]
                                    (when-not (= :test-artifact (-> (get-in ctx [:job :save-artifacts])
                                                                    first
                                                                    :id))
                                      (assoc bc/failure)))
                          :save-artifacts [{:id :test-artifact
                                            :path "test-artifact"}]}}
              r (sut/run-job ctx)]
          (is (bc/success? r))
          (is (true? @saved))))))

  (testing "restores artifacts if configured"
    (let [restored (atom false)]
      (with-redefs [art/restore-artifacts
                    (fn [ctx]
                      (reset! restored true)
                      ctx)]
        (let [ctx {:job {:action (fn [ctx]
                                    (when-not (= :test-artifact (-> (get-in ctx [:job :restore-artifacts])
                                                                    first
                                                                    :id))
                                      (assoc bc/failure)))
                          :restore-artifacts [{:id :test-artifact
                                               :path "test-artifact"}]}}
              r (sut/run-job ctx)]
          (is (bc/success? r))
          (is (true? @restored))))))

  (testing "function returns job config"

    (testing "runs container config when returned"
      (let [job (fn [_]
                   {:container/image "test-image"})
            ctx {:job {:action job}
                 :containers {:type :test}}
            r (sut/run-job ctx)]
        (is (= :run-from-test (:test-result r)))
        (is (bc/success? r))))

    (testing "adds job back to context"
      (let [job {:container/image "test-image"}
            job-fn (fn [_]
                      job)
            ctx {:containers {:type :test}
                 :job {:action job-fn}}
            r (sut/run-job ctx)]
        (is (= job (get-in r [:context :job])))))

    (testing "sets index on the job"
      (let [job-dest (fn [ctx]
                        (when-not (number? (get-in ctx [:job :index]))
                          (assoc bc/failure :message "Index not specified")))
            job-fn (fn [ctx]
                      job-dest)
            job {:action job-fn
                  :index 123}
            ctx {:containers {:type :test}
                 :job job}
            r (sut/run-job ctx)]
        (is (bc/success? r))))))

#_(deftest ->map
  (testing "wraps function in map"
    (is (map? (sut/->map (constantly "ok")))))

  (testing "leaves map as-is"
    (let [m {:key "value"}]
      (is (= m (sut/->map m)))))

  (testing "adds function name to action"
    (is (= "->map" (:name (sut/->map sut/->map))))))
