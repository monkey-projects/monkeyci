(ns monkey.ci.listeners-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [listeners :as sut]
             [storage :as st]]
            [monkey.ci.helpers :as h]))

(defn- random-sid []
  (repeatedly 3 (comp str random-uuid)))

(defn- test-build
  ([sid]
   (-> (zipmap [:customer-id :repo-id :build-id] sid)
       (assoc :status :pending
              :sid sid)))
  ([]
   (test-build (random-sid))))

(deftest save-build
  (testing "updates build entity"
    (h/with-memory-store st
      (let [rt {:storage st}
            sid (random-sid)
            evt {:type :build/end
                 :build (-> (test-build sid)
                            (assoc :status :success))
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (st/sid? (sut/update-build rt evt)))
        (is (true? (st/build-exists? st sid)))
        (is (= :success
               (:status (st/find-build st sid)))))))

  (testing "removes unwanted fields"
    (h/with-memory-store st
      (let [rt {:storage st}
            sid (random-sid)
            evt {:type :build/end
                 :build (-> (test-build sid)
                            (assoc :status :success
                                   :cleanup? true))
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (st/sid? (sut/update-build rt evt)))
        (let [match (st/find-build st sid)]
          (is (not (contains? match :sid)))
          (is (not (contains? match :cleanup?)))))))

  (testing "does not remove existing script info"
    (h/with-memory-store st
      (let [rt {:storage st}
            sid (random-sid)
            script {:jobs {"test-job" {:status :success}}}
            build (-> (test-build sid)
                      (assoc :status :success
                             :script script))
            evt {:type :build/end
                 :build (dissoc build :script)
                 :sid sid
                 :exit 0
                 :result :success}]
        (is (st/sid? (st/save-build st build)))
        (is (st/sid? (sut/update-build rt evt)))
        (let [match (st/find-build st sid)]
          (is (= script (:script match))))))))

(deftest save-script
  (testing "updates script in build"
    (h/with-memory-store st
      (let [rt {:storage st}
            {:keys [sid] :as build} (test-build)
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (st/sid? (st/save-build st build)))
        (is (st/sid? (sut/update-script rt evt)))
        (let [match (st/find-build st sid)]
          (is (= script (:script match)))))))

  (testing "returns `nil` when build not found"
    (h/with-memory-store st
      (let [rt {:storage st}
            {:keys [sid] :as build} (test-build)
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (nil? (sut/update-script rt evt))))))

  (testing "does not overwrite jobs if already present"
    (h/with-memory-store st
      (let [rt {:storage st}
            {:keys [sid] :as build} (-> (test-build)
                                        (assoc :script
                                               {:start-time 100
                                                :status :running
                                                :jobs {"test-job" {:status :success}}}))
            script {:start-time 100
                    :status :running
                    :jobs {"test-job" {}}}
            evt {:type :script/start
                 :sid sid
                 :script script}]
        (is (st/sid? (st/save-build st build)))
        (is (st/sid? (sut/update-script rt evt)))
        (let [match (st/find-build st sid)]
          (is (= :success (get-in match [:script :jobs "test-job" :status]))))))))

(deftest update-job
  (testing "patches build script with job info"
    (h/with-memory-store st
      (let [rt {:storage st}
            {:keys [sid] :as build} (test-build)
            evt {:type :job/start
                 :sid sid
                 :job {:id "test-job"
                       :start-time 120}
                 :message "Starting job"}]
        (is (st/sid? (st/save-build st build)))
        (is (some? (sut/update-job rt evt)))
        (is (= (:job evt)
               (-> (st/find-build st sid)
                   (get-in [:script :jobs "test-job"]))))))))

(deftest build-update-handler
  (testing "creates a fn"
    (is (fn? (sut/build-update-handler {}))))

  (letfn [(verify-build-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    handler (sut/build-update-handler {:storage st})]
                (handler {:type evt-type
                          :build build})
                (is (not= :timeout (h/wait-until #(st/build-exists? st sid) 1000))))))]
    
    (testing "handles `build/start`"
      (verify-build-event :build/start))

    (testing "handles `build/end`"
      (verify-build-event :build/end)))

  (letfn [(verify-script-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    _ (st/save-build st build)
                    script {:jobs {"test-job" {:status :success}}}
                    handler (sut/build-update-handler {:storage st})]
                (handler {:type evt-type
                          :sid sid
                          :script script})
                (is (not= :timeout (h/wait-until (comp some? :script #(st/find-build st sid)) 1000))))))]
    
    (testing "handles `script/start`"
      (verify-script-event :script/start))

    (testing "handles `script/end`"
      (verify-script-event :script/end)))

  (letfn [(verify-job-event [evt-type]
            (h/with-memory-store st
              (let [sid (random-sid)
                    build (test-build sid)
                    job-id (random-uuid)
                    _ (st/save-build st build)
                    job {:id job-id
                         :status :success}
                    handler (sut/build-update-handler {:storage st})]
                (handler {:type evt-type
                          :sid sid
                          :job job})
                (is (not= :timeout (h/wait-until (comp some?
                                                       #(get-in % [:script :jobs job-id])
                                                       #(st/find-build st sid)) 1000))))))]
    
    (testing "handles `job/start`"
      (verify-job-event :job/start))

    (testing "handles `job/end`"
      (verify-job-event :job/end)))  

  (testing "dispatches events in sequence"
    (let [inv (atom {})
          handled (atom 0)]
      (with-redefs [sut/update-job
                    (fn [_ {:keys [sid] :as evt}]
                      (Thread/sleep 100)
                      (if (= :job/start (:type evt))
                        (swap! inv assoc sid [:started])
                        (swap! inv update sid conj :completed))
                      (swap! handled inc))]
        (let [h (sut/build-update-handler {:events {:poster (fn [_]
                                                              (swap! handled inc))}})]
          (h {:type :job/start
              :sid ::first})
          (h {:type :job/start
              :sid ::second})
          (h {:type :job/end
              :sid ::first})
          (h {:type :job/end
              :sid ::second})
          (is (not= :timeout (h/wait-until #(= 4 @handled) 1000)))
          (doseq [[k r] @inv]
            (is (= [:started :completed] r) (str "for id " k))))))))
