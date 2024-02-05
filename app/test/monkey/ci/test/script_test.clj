(ns monkey.ci.test.script-test
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
             [events :as e]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.web.script-api :as script-api]
            [monkey.ci.build.core :as bc]
            [monkey.ci.test.helpers :as h]
            [monkey.socket-async
             [core :as sa]
             [uds :as uds]]
            [org.httpkit.fake :as hf]
            [schema.core :as s]))

(defn with-listening-socket [f]
  (let [p (u/tmp-file "test-" ".sock")]
    (try
      (let [bus (e/make-bus)
            server (script-api/listen-at-socket p {:event-bus bus
                                                   :public-api script-api/local-api})]
        (try
          (f p bus)
          (finally
            (script-api/stop-server server))))
      (finally
        (uds/delete-address p)))))

(deftest resolve-pipelines
  (testing "returns vector as-is"
    (is (= [::pipelines] (sut/resolve-pipelines [::pipelines] {}))))

  (testing "invokes fn"
    (is (= [::pipelines] (sut/resolve-pipelines (constantly [::pipelines]) {}))))

  (testing "wraps single pipeline"
    (let [p (bc/map->Pipeline {})]
      (is (= [p] (sut/resolve-pipelines p {}))))))

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
    (is (bc/failed? (sut/exec-script! {:script-dir "examples/invalid-script"}))))
  
  (testing "connects to listening socket if specified"
    (with-listening-socket
      (fn [socket-path bus]
        (let [in (e/wait-for bus :script/start (map identity))]
          ;; Execute the script, we expect at least one incoming event
          (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"
                                              :build {:build-id "test-build"}
                                              :api {:socket socket-path}})))
          ;; Try to read a message on the channel
          (is (= in (-> (ca/alts!! [in (ca/timeout 500)])
                        (second)))))))))

(deftest make-client
  (testing "creates client for domain socket"
    (is (some? (sut/make-client {:api {:socket "test.sock"}}))))

  (testing "creates client for host"
    (hf/with-fake-http ["http://test/script/swagger.json" 200]
      (is (some? (sut/make-client {:api {:url "http://test"}}))))))

(deftest run-pipelines
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-pipelines {} []))))

  (testing "success if all steps succeed"
    (is (bc/success? (->> [(bc/pipeline {:steps [(constantly bc/success)]})]
                          (sut/run-pipelines {})))))

  (testing "runs a single pipline"
    (is (bc/success? (->> (bc/pipeline {:name "single"
                                        :steps [(constantly bc/success)]})
                          (sut/run-pipelines {})))))
  
  (testing "fails if a step fails"
    (is (bc/failed? (->> [(bc/pipeline {:steps [(constantly bc/failure)]})]
                         (sut/run-pipelines {})))))

  (testing "success if step returns `nil`"
    (is (bc/success? (->> (bc/pipeline {:name "nil"
                                        :steps [(constantly nil)]})
                          (sut/run-pipelines {})))))

  (testing "runs pipeline by name, if given"
    (is (bc/success? (->> [(bc/pipeline {:name "first"
                                         :steps [(constantly bc/success)]})
                           (bc/pipeline {:name "second"
                                         :steps [(constantly bc/failure)]})]
                          (sut/run-pipelines {:pipeline "first"})))))

  (testing "posts events through api"
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
                    pipelines [(bc/pipeline {:name "test"
                                             :steps [(constantly bc/success)]})]
                    ctx {:api {:client client}}]
                (is (bc/success? (sut/run-pipelines ctx pipelines)))
                (is (pos? (count @events-posted)))
                (is (true? (-> (map :type @events-posted)
                               (set)
                               (contains? expected-type))))))]

      ;; Run a test for each type
      (->> [:pipeline/start
            :pipeline/end
            :step/start
            :step/end]
           (map (fn [t]
                  (testing (str t)
                    (verify-evt t))))
           (doall))))

  (testing "skips `nil` pipelines"
    (is (bc/success? (->> [(bc/pipeline {:steps [(constantly bc/success)]})
                           nil]
                          (sut/run-pipelines {})))))

  (testing "handles pipeline seq that's not a vector"
    (let [r (->> '((bc/pipeline {:steps [(constantly bc/success)]})
                   (bc/pipeline {:steps [(constantly bc/success)]}))
                 (sut/run-pipelines {}))]
      (is (bc/success? r))
      (is (= 2 (count (:pipelines r)))))))

(defmethod c/run-container :test [ctx]
  {:test-result :run-from-test
   :context ctx
   :status :success
   :exit 0})

(deftest pipeline-run-step
  (testing "fails on invalid config"
    (is (thrown? Exception (sut/run-step {:step (constantly bc/success)}))))

  (testing "executes action from map"
    (is (bc/success? (sut/run-step {:step {:action (constantly bc/success)}}))))

  (testing "executes in container if configured"
    (let [ctx {:step {:container/image "test-image"}
               :containers {:type :test}}
          r (sut/run-step ctx)]
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
                      (->> (get-in ctx [:step :caches])
                           (mapv :id)))]
        (let [ctx {:step {:action (fn [ctx]
                                    (when-not (= [:test-cache] (get-in ctx [:step :caches]))
                                      bc/failure))
                          :caches [{:id :test-cache
                                    :path "test-cache"}]}}
              r (sut/run-step ctx)]
          (is (bc/success? r))
          (is (true? @saved))))))

  (testing "saves artifacts if configured"
    (let [saved (atom false)]
      (with-redefs [art/save-artifacts
                    (fn [ctx]
                      (reset! saved true)
                      ctx)]
        (let [ctx {:step {:action (fn [ctx]
                                    (when-not (= :test-artifact (-> (get-in ctx [:step :save-artifacts])
                                                                    first
                                                                    :id))
                                      (assoc bc/failure)))
                          :save-artifacts [{:id :test-artifact
                                            :path "test-artifact"}]}}
              r (sut/run-step ctx)]
          (is (bc/success? r))
          (is (true? @saved))))))

  (testing "restores artifacts if configured"
    (let [restored (atom false)]
      (with-redefs [art/restore-artifacts
                    (fn [ctx]
                      (reset! restored true)
                      ctx)]
        (let [ctx {:step {:action (fn [ctx]
                                    (when-not (= :test-artifact (-> (get-in ctx [:step :restore-artifacts])
                                                                    first
                                                                    :id))
                                      (assoc bc/failure)))
                          :restore-artifacts [{:id :test-artifact
                                               :path "test-artifact"}]}}
              r (sut/run-step ctx)]
          (is (bc/success? r))
          (is (true? @restored))))))

  (testing "function returns step config"

    (testing "runs container config when returned"
      (let [step (fn [_]
                   {:container/image "test-image"})
            ctx {:step {:action step}
                 :containers {:type :test}}
            r (sut/run-step ctx)]
        (is (= :run-from-test (:test-result r)))
        (is (bc/success? r))))

    (testing "adds step back to context"
      (let [step {:container/image "test-image"}
            step-fn (fn [_]
                      step)
            ctx {:containers {:type :test}
                 :step {:action step-fn}}
            r (sut/run-step ctx)]
        (is (= step (get-in r [:context :step])))))

    (testing "sets index on the step"
      (let [step-dest (fn [ctx]
                        (when-not (number? (get-in ctx [:step :index]))
                          (assoc bc/failure :message "Index not specified")))
            step-fn (fn [ctx]
                      step-dest)
            step {:action step-fn
                  :index 123}
            ctx {:containers {:type :test}
                 :step step}
            r (sut/run-step ctx)]
        (is (bc/success? r))))))

(deftest ->map
  (testing "wraps function in map"
    (is (map? (sut/->map (constantly "ok")))))

  (testing "leaves map as-is"
    (let [m {:key "value"}]
      (is (= m (sut/->map m)))))

  (testing "adds function name to action"
    (is (= "->map" (:name (sut/->map sut/->map))))))
