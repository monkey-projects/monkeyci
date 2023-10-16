(ns monkey.ci.test.script-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [martian
             [core :as martian]
             [test :as mt]]
            [monkey.ci
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
            [schema.core :as s]))

(defn with-listening-socket [f]
  (let [p (u/tmp-file "test-" ".sock")]
    (try
      (let [server (script-api/listen-at-socket p {})]
        (try
          (f p)
          (finally
            (script-api/stop-server server))))
      (finally
        (uds/delete-address p)))))

(deftest exec-script!
  (testing "executes basic clj script from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"}))))

  (testing "executes script shell from location"
    (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-script"}))))
  
  (testing "connects to listening socket if specified"
    (with-listening-socket
      (fn [socket-path]
        (let [in (ca/chan 1)]
          ;; Execute the script, we expect at least one incoming event
          (is (bc/success? (sut/exec-script! {:script-dir "examples/basic-clj"
                                              :api-socket socket-path})))
          ;; Try to read a message on the channel
          (is (= in (-> (ca/alts!! [in (ca/timeout 500)])
                        (second)))))))))

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
                                                               {:status 200})}))
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
           (doall)))))

(defmethod c/run-container :test [ctx]
  {:test-result :run-from-test
   :exit 0})

(deftest pipeline-run-step
  (testing "executes function directly"
    (is (= :ok (sut/run-step (constantly :ok) {}))))

  (testing "executes action from map"
    (is (= :ok (sut/run-step {:action (constantly :ok)} {}))))

  (testing "executes in container if configured"
    (let [config {:container/image "test-image"}
          r (sut/run-step config {:containers {:type :test}})]
      (is (= :run-from-test (:test-result r)))
      (is (bc/success? r)))))
