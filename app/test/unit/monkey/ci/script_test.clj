(ns monkey.ci.script-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as spec]
            [manifold
             [bus :as mb]
             [deferred :as md]]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [runtime :as rt]
             [script :as sut]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.core :as ec]
            [monkey.ci.spec.events :as se]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.aleph-test :as at]))

(defn dummy-job
  ([r]
   (bc/action-job ::test-job (constantly r)))
  ([]
   (dummy-job bc/success)))

(defrecord FakeJob [id f]
  j/Job
  (execute! [this ctx]
    (f ctx)))

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
            (-> {:build (b/set-script-dir {} (str "examples/" d))
                 :events (h/fake-events)
                 :event-bus {:bus (mb/event-bus)}}
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

(deftest run-all-jobs
  (let [rt {:events (h/fake-events)
            :event-bus {:bus (mb/event-bus)}
            :api {:client ::fake-api}}]
    (testing "success if no pipelines"
      (is (bc/success? (sut/run-all-jobs rt []))))

    (testing "success if all jobs succeed"
      (is (bc/success? (->> [(dummy-job bc/success)]
                            (sut/run-all-jobs rt)))))
    
    (testing "success if jobs skipped"
      (is (bc/success? (->> [(dummy-job bc/skipped)]
                            (sut/run-all-jobs rt)))))

    (testing "fails if a job fails"
      (is (bc/failed? (->> [(dummy-job bc/failure)]
                           (sut/run-all-jobs rt)))))

    (testing "success if job returns `nil`"
      (is (bc/success? (->> [(dummy-job nil)]
                            (sut/run-all-jobs rt)))))

    (testing "runs jobs filtered by pipeline name"
      (is (bc/success? (->> [(bc/pipeline {:name "first"
                                           :jobs [(dummy-job bc/success)]})
                             (bc/pipeline {:name "second"
                                           :jobs [(dummy-job bc/failure)]})]
                            (sut/run-all-jobs (assoc rt :pipeline "first"))))))

    (testing "returns all job results"
      (let [job (bc/action-job "test-job" (constantly bc/success))
            result (->> [job]
                        (sut/run-all-jobs rt)
                        :jobs)]
        (is (= ["test-job"] (keys result)))
        (is (= bc/success (get-in result ["test-job" :result])))))

    (testing "passes full runtime to jobs"
      (let [job (->FakeJob "test-job" (fn [ctx]
                                        (if (every? (set (keys ctx)) [:build :api :job :events])
                                          bc/success
                                          (bc/with-message bc/failure (str "Keys:" (keys ctx))))))
            result (->> [job]
                        (sut/run-all-jobs rt)
                        :jobs)]
        (is (= bc/success (get-in result ["test-job" :result])))))

    #_(testing "does not run jobs when canceled"
      ;; FIXME The event is dropped because there are no listeners at this point
      (is (true? @(mb/publish! (get-in rt [:event-bus :bus]) :build/canceled {:type :build/canceled})))
      (let [job (bc/action-job "test-job" (constantly bc/success))
            result (->> [job]
                        (sut/run-all-jobs rt)
                        :jobs)]
        (is (= :skipped (-> result (get "test-job") :result :status)))))))

(deftest canceled-evt
  (testing "holds `build/canceled` event"
    (let [bus (mb/event-bus)
          c (sut/canceled-evt bus)]
      (is (md/deferred? c))
      (is (true? @(mb/publish! bus :build/canceled ::test-evt)))
      (is (= ::test-evt (deref c 1000 ::timeout))))))

(deftest run-all-jobs*
  (letfn [(verify-script-evt [evt-type jobs verifier]
            (let [e (h/fake-events)
                  rt {:events e
                      :event-bus {:bus (mb/event-bus)}
                      :build {:sid (h/gen-build-sid)}}]
              (is (some? (sut/run-all-jobs* rt jobs)))
              (let [l (->> (h/received-events e)
                           (h/first-event-by-type evt-type))]
                (is (some? l))
                (is (spec/valid? ::se/event l)
                    (spec/explain-str ::se/event l))
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
           (is (= 1 (count (:jobs evt))))
           (let [job (-> evt :jobs first)]
             (is (some? job))
             (is (= :pending (:status job))))))))
    
    (testing "posts `:script/end` event with script status"
      (let [result (assoc bc/success :message "Test result")
            job (bc/action-job "test-job" (constantly result))]
        (verify-script-end-evt
         [job]
         (fn [evt]
           (is (= :success (:status evt)))))))

    (testing "posts `:job/skipped` event for all skipped jobs"
      (let [failing (bc/action-job "failing-job"
                                   (constantly bc/failure))
            skipped (bc/action-job "skipped-job"
                                   (fn [_] (throw (ex-info "Should not be invoked" {})))
                                   {:dependencies ["failing-job"]})]
        (verify-script-evt
         :job/skipped
         [failing skipped]
         (fn [evt]
           (is (= "skipped-job" (:job-id evt)))))))

    (testing "adds job labels to event"
      (verify-script-start-evt
       [(bc/action-job "test-job" (constantly bc/success) {:labels {:key "value"}})]
       (fn [evt]
         (is (= {:key "value"}
                (-> evt :jobs first :labels))))))

    (testing "adds job dependencies"
      (let [jobs [(bc/action-job "first-job" (constantly bc/success))
                  (bc/action-job "second-job" (constantly bc/success)
                                 {:dependencies ["first-job"]})]]
        (verify-script-start-evt
         jobs
         (fn [evt]
           (is (= ["first-job"]
                  (-> evt :jobs second :dependencies)))))))))

(deftest error-catching-job
  (let [ctx {:events (h/fake-events)}]
    (testing "invokes target"
      (is (bc/success? @(j/execute! (sut/->ErrorCatchingJob (dummy-job)) ctx))))
    
    (testing "catches sync errors, returns failure"
      (let [{:keys [recv] :as e} (h/fake-events)
            job (bc/action-job ::failing-job (fn [_] (throw (ex-info "Test error" {}))))
            f (sut/->ErrorCatchingJob job)]
        (is (bc/failed? @(j/execute! f ctx)))))

    (testing "catches async errors, returns failure"
      (let [job (bc/action-job ::failing-async-job (fn [_] (md/error-deferred (ex-info "Test error" {}))))
            f (sut/->ErrorCatchingJob job)]
        (is (bc/failed? @(j/execute! f ctx)))))))
