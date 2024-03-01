(ns monkey.ci.script-test
  (:require [clojure.test :refer :all]
            [manifold.deferred :as md]
            [monkey.ci
             [jobs :as j]
             [runtime :as rt]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.web.script-api :as script-api]
            [monkey.ci.build.core :as bc]
            [monkey.ci.helpers :as h]
            [monkey.socket-async.uds :as uds]
            [org.httpkit.fake :as hf]))

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
  (testing "invokes fn"
    (let [job (dummy-job)
          p (bc/pipeline {:jobs [job]})]
      (is (= [job] (sut/resolve-jobs (constantly p) {})))))

  (testing "auto-assigns ids to jobs"
    (let [jobs (repeat 10 (bc/action-job nil (constantly ::test)))
          p (sut/resolve-jobs (vec jobs) {})]
      (is (not-empty p))
      (is (every? :id p))
      (is (= (count jobs) (count (distinct (map :id p)))))))

  (testing "assigns id as metadata to function"
    (let [p (sut/resolve-jobs [(bc/action-job nil (constantly ::ok))] {})]
      (is (= 1 (count p)))
      (is (= "job-1" (-> p
                         first
                         bc/job-id)))))

  (testing "does not overwrite existing id"
    (is (= ::test-id (-> {:jobs [{:id ::test-id
                                  :action (constantly :ok)}]}
                         (bc/pipeline)
                         (sut/resolve-jobs {})
                         first
                         bc/job-id))))

  (testing "returns jobs as-is"
    (let [jobs (repeatedly 10 dummy-job)]
      (is (= jobs (sut/resolve-jobs jobs {})))))

  (testing "resolves job resolvables"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs [(constantly job)] {}))))))

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

  (testing "returns all job results"
    (let [job (bc/action-job "test-job" (constantly bc/success))
          result (->> [job]
                      (sut/run-all-jobs {})
                      :jobs)]
      (is (= ["test-job"] (keys result)))
      (is (= bc/success (get-in result ["test-job" :result]))))))

(deftest run-all-jobs*
  (testing "posts `:script/end` event with job results"
    (let [result (assoc bc/success :message "Test result")
          job (bc/action-job "test-job" (constantly result))
          events (atom [])
          rt {:events {:poster (partial swap! events conj)}}]
      (is (some? (sut/run-all-jobs* rt [job])))
      (is (not-empty @events))
      (is (contains? (set (map :type @events)) :script/end))
      (let [l (last @events)]
        (is (= :script/end (:type l)))
        (is (= {:result result}
               (get-in l [:jobs "test-job"]))))))

  (testing "adds job labels to event"
    (let [job (bc/action-job "test-job" (constantly bc/success) {:labels {:key "value"}})
          events (atom [])
          rt {:events {:poster (partial swap! events conj)}}]
      (is (some? (sut/run-all-jobs* rt [job])))
      (let [l (last @events)]
        (is (= :script/end (:type l)))
        (is (= {:key "value"}
               (get-in l [:jobs "test-job" :labels]))))))

  (testing "adds job dependencies to end event"
    (let [jobs [(bc/action-job "first-job" (constantly bc/success))
                (bc/action-job "second-job" (constantly bc/success)
                               {:dependencies ["first-job"]})]
          events (atom [])
          rt {:events {:poster (partial swap! events conj)}}]
      (is (some? (sut/run-all-jobs* rt jobs)))
      (let [l (last @events)]
        (is (= :script/end (:type l)))
        (is (= ["first-job"]
               (get-in l [:jobs "second-job" :dependencies]))))))

  (testing "marks job as successful if it returns `nil`"
    (let [jobs [(bc/action-job "nil-job" (constantly nil))]
          events (atom [])
          rt {:events {:poster (partial swap! events conj)}}]
      (is (some? (sut/run-all-jobs* rt jobs)))
      (let [l (last @events)]
        (is (bc/success?
             (get-in l [:jobs "nil-job" :status])))))))

(deftest event-firing-job
  (testing "invokes target"
    (is (bc/success? @(j/execute! (sut/->EventFiringJob (dummy-job)) {}))))
  
  (testing "posts event at start and stop"
    (let [events (atom [])
          rt {:events {:poster (partial swap! events conj)}}
          job (dummy-job)
          f (sut/->EventFiringJob job)]
      (is (some? @(j/execute! f rt)))
      (is (= 2 (count @events)))
      (is (= :job/start (:type (first @events))))
      (is (= :job/end (:type (second @events))))))

  (testing "catches sync errors, returns failure"
    (let [events (atom [])
          rt {:events {:poster (partial swap! events conj)}}
          job (bc/action-job ::failing-job (fn [_] (throw (ex-info "Test error" {}))))
          f (sut/->EventFiringJob job)]
      (is (bc/failed? @(j/execute! f rt)))
      (is (= 2 (count @events)) "expected job end event")))

  (testing "catches async errors, returns failure"
    (let [events (atom [])
          rt {:events {:poster (partial swap! events conj)}}
          job (bc/action-job ::failing-async-job (fn [_] (md/error-deferred (ex-info "Test error" {}))))
          f (sut/->EventFiringJob job)]
      (is (bc/failed? @(j/execute! f rt)))
      (is (= 2 (count @events)) "expected job end event"))))


