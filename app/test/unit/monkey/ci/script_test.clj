(ns monkey.ci.script-test
  (:require [aleph.http :as http]
            [clojure.test :refer :all]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [runtime :as rt]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.helpers :as h]))

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
  (letfn [(exec-in-dir [d]
            (-> {:build (b/set-script-dir {} (str "examples/" d))}
                (sut/exec-script!)))]
    
    (testing "executes basic clj script from location"
      (is (bc/success? (exec-in-dir "basic-clj"))))

    (testing "executes script shell from location"
      (is (bc/success? (exec-in-dir "basic-script"))))

    (testing "executes dynamic pipelines"
      (is (bc/success? (exec-in-dir "dynamic-pipelines"))))

    (testing "skips `nil` pipelines"
      (is (bc/success? (exec-in-dir "conditional-pipelines"))))

    (testing "invalid script"
      (let [r (exec-in-dir "invalid-script")]
        (testing "fails"
          (is (bc/failed? r)))

        (testing "returns compiler error"
          (is (= "Unable to resolve symbol: This in this context" (:message r))))))))

(deftest setup-runtime
  (testing "creates client if configured"
    (is (fn? (-> {:api {:url "http://test"
                        :token "test-token"}}
                 (rt/setup-runtime :api)
                 :client))))

  (testing "no client if not corretly configured"
    (is (nil? (-> {:api {:url "http://test"}}
                  (rt/setup-runtime :api)
                  :client)))))

(deftest make-client
  (testing "client invokes http request, parses body as edn"
    (with-redefs [http/request (constantly (md/success-deferred
                                            {:status 200
                                             :body "\"ok\""}))]
      (let [c (sut/make-client {:api {:url "http://test"}})]
        (is (= "ok" @(c {:method :get :url "/something"}))))))

  (testing "throws on error"
    (with-redefs [http/request (constantly (md/success-deferred
                                            {:status 400
                                             :body "Client error"}))]
      (let [c (sut/make-client {:api {:url "http://test"}})]
        (is (thrown? Exception @(c {:method :get :url "/something"}))))))

  (testing "prefixes url to path"
    (with-redefs [http/request (fn [{:keys [url]}]
                                 (md/success-deferred
                                  {:status 200
                                   :body (pr-str url)}))]
      (let [c (sut/make-client {:api {:url "http://test"}})]
        (is (= "http://test/something" @(c {:method :get :url "/something"})))))))

(deftest run-all-jobs
  (testing "success if no pipelines"
    (is (bc/success? (sut/run-all-jobs {} []))))

  (testing "success if all jobs succeed"
    (is (bc/success? (->> [(dummy-job bc/success)]
                          (sut/run-all-jobs {})))))
  
  (testing "success if jobs skipped"
      (is (bc/success? (->> [(dummy-job bc/skipped)]
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
  (letfn [(verify-script-evt [evt-type jobs verifier]
            (let [{:keys [recv] :as e} (h/fake-events)
                  rt {:events e
                      :build {:sid ["test-cust" "test-repo"]}}]
              (is (some? (sut/run-all-jobs* rt jobs)))
              (is (not-empty @recv))
              (is (contains? (set (map :type @recv)) :script/end))
              (let [l (->> @recv
                           (filter (comp (partial = evt-type) :type))
                           (first))]
                (is (some? l))
                (verifier l))))
          (verify-script-end-evt [jobs verifier]
            (verify-script-evt :script/end jobs verifier))
          (verify-script-start-evt [jobs verifier]
            (verify-script-evt :script/start jobs verifier))]

    (testing "posts `:script/start` event with pending jobs"
      (let [job (bc/action-job "test-job" (constantly nil))]
        (verify-script-start-evt
         [job]
         (fn [evt]
           (is (= 1 (count (get-in evt [:script :jobs]))))
           (is (some? (get-in evt [:script :jobs "test-job"])))
           (is (= :pending
                  (get-in evt [:script :jobs "test-job" :status])))))))
    
    (testing "posts `:script/end` event with job status"
      (let [result (assoc bc/success :message "Test result")
            job (bc/action-job "test-job" (constantly result))]
        (verify-script-end-evt
         [job]
         (fn [evt]
           (is (= :success
                  (get-in evt [:script :jobs "test-job" :status])))))))

    (testing "adds job labels to event"
      (verify-script-end-evt
       [(bc/action-job "test-job" (constantly bc/success) {:labels {:key "value"}})]
       (fn [evt]
         (is (= {:key "value"}
                (get-in evt [:script :jobs "test-job" :labels]))))))

    (testing "adds job dependencies to end event"
      (let [jobs [(bc/action-job "first-job" (constantly bc/success))
                  (bc/action-job "second-job" (constantly bc/success)
                                 {:dependencies ["first-job"]})]]
        (verify-script-end-evt
         jobs
         (fn [evt]
           (is (= ["first-job"]
                  (get-in evt [:script :jobs "second-job" :dependencies])))))))

    (testing "marks job as successful if it returns `nil`"
      (verify-script-end-evt
       [(bc/action-job "nil-job" (constantly nil))]
       (fn [evt]
         (is (bc/success?
              (get-in evt [:script :jobs "nil-job" :status]))))))))

(deftest event-firing-job
  (testing "invokes target"
    (is (bc/success? @(j/execute! (sut/->EventFiringJob (dummy-job)) {}))))
  
  (testing "posts event at start and stop"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e}
          job (dummy-job)
          f (sut/->EventFiringJob job)]
      (is (some? @(j/execute! f rt)))
      (is (= 2 (count @recv)))
      (is (= :job/start (:type (first @recv))))
      (is (= :job/end (:type (second @recv))))))

    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e}
          job (dummy-job)
          f (sut/->EventFiringJob job)]
      (is (some? @(j/execute! f rt)))
      
      (testing "start event has `start-time`"
        (is (number? (-> @recv first :job :start-time))))

      (testing "end event has `start-time` and `end-time`"
        (let [job (-> @recv second :job)]
          (is (number? (:start-time job)))
          (is (number? (:end-time job))))))

  (testing "catches sync errors, returns failure"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e}
          job (bc/action-job ::failing-job (fn [_] (throw (ex-info "Test error" {}))))
          f (sut/->EventFiringJob job)]
      (is (bc/failed? @(j/execute! f rt)))
      (is (= 2 (count @recv)) "expected job end event")))

  (testing "catches async errors, returns failure"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e}
          job (bc/action-job ::failing-async-job (fn [_] (md/error-deferred (ex-info "Test error" {}))))
          f (sut/->EventFiringJob job)]
      (is (bc/failed? @(j/execute! f rt)))
      (is (= 2 (count @recv)) "expected job end event")))

  (testing "event contains job id"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e}
          job (dummy-job)
          f (sut/->EventFiringJob job)]
      (is (every? (comp (partial = (:id job)) :id :job) @recv))))

  (testing "event contains build sid"
    (let [{:keys [recv] :as e} (h/fake-events)
          sid ["test-cust" "test-repo"]
          rt {:events e
              :build {:sid sid}}
          job (dummy-job)
          f (sut/->EventFiringJob job)]
      (is (every? (comp some? :sid) @recv)))))
