(ns monkey.ci.containers.log-ingest-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest testing is]]
            [manifold.stream :as ms]
            [monkey.ci.containers.log-ingest :as sut]
            [monkey.ci.events.mailman.interceptors :as mi]
            [monkey.ci.test.helpers :as h]))

(deftest routes
  (testing "handles required events"
    (let [exp #{:command/start :command/end :job/initializing}
          r (sut/make-routes {})
          handled (set (map first r))]
      (is (= exp handled)))))

(deftest command-start
  (testing "starts ingesting log files"
    (is (= {:ingest/start ["out.log" "err.log"]}
           (-> {:event
                {:type :command/start
                 :stdout "out.log"
                 :stderr "err.log"}}
               (sut/command-start))))))

(deftest command-end
  (testing "stops ingesting log files"
    (is (= {:ingest/stop ["out.log" "err.log"]}
           (-> {:event
                {:type :command/end
                 :stdout "out.log"
                 :stderr "err.log"}}
               (sut/command-end))))))

(deftest add-config
  (let [conf {:stream-creator (constantly ::test)}
        {:keys [enter] :as i} (sut/add-config conf)]
    (is (keyword? (:name i)))

    (let [r (enter {})]
      (testing "sets config in context"
        (is (map? (sut/get-config r))))

      (testing "sets stream creator"
        (is (fn? (sut/get-stream-creator r)))))))

(deftest start-ingest
  (h/with-tmp-dir dir
    (let [{:keys [leave] :as i} sut/start-ingest
          s (ms/stream 1)
          log-dir (fs/create-dir (fs/path dir "logs"))
          p (fs/path log-dir "test.log")
          r (-> {:event
                 {:type :command/start
                  :sid ::build-sid
                  :job-id ::job-id}}
                (sut/set-stream-creator (fn [f opts]
                                          (when (and (= ::build-sid (:sid opts))
                                                     (= ::job-id (:job-id opts))
                                                     (= (str p) f))
                                            s)))
                (mi/set-result {:ingest/start ["/some/container/dir/test.log"]})
                (sut/set-local-dir [::build-sid ::job-id] (str dir))
                (leave))]
      (is (keyword? (:name i)))
      
      (testing "starts ingestion of local file"
        (is (nil? (spit (fs/file p) "test log entry")))
        (is (map? @(ms/try-take! s 300))))

      (testing "adds to ingest streams"
        (let [a (sut/get-ingest-streams r)]
          (is (= 1 (count a)))
          (is (= [(str p)] (keys a)))
          (is (= s (get-in a [(str p) :stream])))))

      (testing "clears result"
        (is (nil? (mi/get-result r))))

      (testing "stores in state"
        (is (not-empty (mi/get-state r))))

      (is (nil? (ms/close! s)))

      (testing "ignores nonexisting files"
        (is (empty? (-> {}
                        (sut/set-stream-creator (fn [& _] (throw (ex-info "Unexpected" {}))))
                        (mi/set-result {:ingest/start ["/some/nonexisting/file.txt"]})
                        (leave)
                        (sut/get-ingest-streams))))))))

(deftest stop-ingest
  (h/with-tmp-dir dir
    (let [{:keys [leave] :as i} sut/stop-ingest
          log-dir (fs/create-dir (fs/path dir "logs"))
          p (str (fs/path log-dir "existing.log"))
          _ (fs/create-file p)
          s (ms/stream)
          ctx (-> {:event
                   {:sid ::build-sid
                    :job-id ::job-id}}
                  (sut/add-ingest-streams [{:path p
                                            :stream s}])
                  (mi/set-result {:ingest/stop ["/some/container/dir/existing.log"]})
                  (sut/set-local-dir [::build-sid ::job-id] (str dir))
                  (leave))]
      (is (keyword? (:name i)))
      
      (testing "closes stream"
        (is (ms/closed? s)))

      (testing "removes streams from context"
        (is (empty? (sut/get-ingest-streams ctx))))

      (testing "clears result"
        (is (nil? (mi/get-result ctx))))

      (testing "ignores nonexisting files"
        (is (some? (-> {}
                       (mi/set-result {:ingest/stop ["nonexisting.txt"]})
                       (leave))))))))

(deftest save-local-dir
  (let [{:keys [leave] :as i} sut/save-local-dir]
    (is (keyword? (:name i)))
    
    (testing "`leave` adds local dir for job to state"
      (is (= "/local/dir"
             (-> {:event
                  {:type :job-initializing
                   :local-dir "/local/dir"
                   :sid ["test" "build"]
                   :job-id "test-job"}}
                 (leave)
                 (sut/get-local-dir [["test" "build"] "test-job"])))))))
