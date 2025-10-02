(ns monkey.ci.local.console-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman.interceptors :as mi]
            [monkey.ci.local.console :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest result->state
  (let [{:keys [leave] :as i} sut/result->state]
    (is (keyword? (:name i)))

    (let [r (leave {:result ::test-result})]
      (testing "erases result"
        (is (nil? (:result r))))

      (testing "sets state to result"
        (is (= ::test-result (mi/get-state r)))))))

(deftest build-init
  (testing "sets build in state as result"
    (is (= ::test-build
           (-> {:event
                {:build ::test-build}}
               (mi/set-state {})
               (sut/build-init)
               (sut/get-build))))))

(deftest build-start
  (testing "sets start time in build"
    (is (= ::test-time
           (-> {:event
                {:time ::test-time}}
               (mi/set-state {:build {:build-id "test-build"}})
               (sut/build-start)
               (sut/get-build)
               :start-time)))))

(deftest build-start
  (let [b (-> {:event
                {:time ::test-time
                 :status :success}}
               (mi/set-state {:build {:build-id "test-build"}})
               (sut/build-end)
               (sut/get-build))]
    (testing "sets end time in build"
      (is (= ::test-time (:end-time b))))

    (testing "sets status"
      (is (= :success (:status b))))))

(deftest script-start
  (testing "adds sorted jobs to state"
    (is (= ["job-1" "job-2"]
           (->> {:event
                 {:jobs [{:id "job-2"
                          :dependencies ["job-1"]}
                         {:id "job-1"}]}}
                (sut/script-start)
                (sut/get-jobs)
                (map :id))))))

(deftest script-end
  (testing "sets script msg in build"
    (is (= "test msg"
           (-> {:event
                {:message "test msg"}}
               (sut/script-end)
               (sut/get-build)
               :script-msg)))))

(deftest job-init
  (testing "sets job status to `initializing`"
    (is (= :initializing (-> {:event {:job-id "test-job"}}
                             (mi/set-state {:jobs [{:id "test-job"
                                                    :status :pending}]})
                             (sut/job-init)
                             (sut/get-jobs)
                             first
                             :status)))))

(deftest job-start
  (let [r (-> {:event {:job-id "test-job"
                       :time ::start-time}}
              (mi/set-state {:jobs [{:id "test-job"
                                     :status :pending}]})
              (sut/job-start)
              (sut/get-jobs)
              first)]
    (testing "sets job status to `running`"
      (is (= :running (:status r))))

    (testing "sets job start time"
      (is (= ::start-time (:start-time r))))))

(deftest job-end
  (let [r (-> {:event {:job-id "test-job"
                       :time ::test-time
                       :status :success
                       :message "test msg"
                       :result {:output "test output"}}}
              (mi/set-state {:jobs [{:id "test-job"
                                     :status :running}]})
              (sut/job-end)
              (sut/get-jobs)
              first)]
    (testing "sets job status"
      (is (= :success (:status r))))

    (testing "sets job end time"
      (is (= ::test-time (:end-time r))))

    (testing "sets job output"
      (is (= "test output" (:output r))))

    (testing "sets job message"
      (is (= "test msg" (:message r))))))

(deftest routes
  (testing "handles build and job events"
    (let [exp [:build/initializing
               :build/start
               :build/end
               :script/start
               :script/end
               :job/initializing
               :job/start
               :job/end]
          r (->> (sut/make-routes {:state (atom {})})
                 (map first)
                 (set))]
      (doseq [e exp]
        (is (r e) (str "should handle " e))))))

(deftest periodical-renderer
  (testing "`start` starts periodical render loop"
    (let [rendered (atom [])
          state (atom {})
          r (-> (sut/map->PeriodicalRenderer
                 {:state state
                  :renderer (partial swap! rendered conj)
                  :interval 100})
                (co/start))]
      (is (some? (reset! state {:test :state})))
      (is (fn? (:render-stop r)))
      (is (not= :timeout (h/wait-until #(not-empty (remove empty? @rendered)) 500)))
      (is (= {:test :state}
             (->> @rendered
                  (remove empty?)
                  (first)))
          "passes state to renderer")))

  (testing "`stop`"
    (let [stopped? (atom false)
          rendered? (atom false)]
      (is (nil? (-> (sut/map->PeriodicalRenderer
                     {:render-stop (fn []
                                     (reset! stopped? true))
                      :state (atom nil)
                      :renderer (fn [_]
                                  (reset! rendered? true))})
                    (co/stop)
                    :render-stop)))
      (testing "ends render loop"
        (is (true? @stopped?)))

      (testing "performs final rendering"
        (is (true? @rendered?))))))

(deftest console-renderer
  (let [src (fn [state]
              [(str "state to print: " state)])
        r (sut/console-renderer src)]
    (testing "is a fn"
      (is (fn? r)))

    (testing "prints to screen"
      (let [w (java.io.StringWriter.)]
        (binding [*out* w]
          (is (map? (r {:key "test-state"}))))
        (is (cs/starts-with?
             (.trim (.toString w))
             "state to print: "))))))
