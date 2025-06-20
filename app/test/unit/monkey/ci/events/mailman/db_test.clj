(ns monkey.ci.events.mailman.db-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [manifold.bus :as mb]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman
             [db :as sut]
             [interceptors :as emi]]
            [monkey.ci.spec.events :as se]
            [monkey.ci.test.helpers :as h]))

(defn- validate-spec [spec obj]
  (is (spec/valid? spec obj)
      (spec/explain-str spec obj)))

(deftest org-credits
  (h/with-memory-store s
    (let [org (h/gen-org)]
      (is (some? (st/save-org s org)))
      (is (some? (st/save-org-credit s {:org-id (:id org)
                                        :amount 100})))
      
      (testing "adds available org credits to context"
        (let [{:keys [enter] :as i} sut/org-credits]
          (is (= ::sut/org-credits (:name i)))
          (is (fn? enter))
          (is (= 100M
                 (-> {:event {:build
                              {:org-id (:id org)}}}
                     (emi/set-db s)
                     (enter)
                     (sut/get-credits)))))))))

(deftest assign-build-idx
  (h/with-memory-store s
    (let [{:keys [enter] :as i} sut/assign-build-idx
          build (h/gen-build)
          get-sid (apply juxt st/build-sid-keys)]
      (is (keyword? (:name i)))
      
      (testing "`enter` assigns build index, unique to the repo"
        (let [repo (h/gen-repo)
              org (-> (h/gen-org)
                      (assoc :repos {(:id repo) repo}))
              build {:org-id (:id org)
                     :repo-id (:id repo)}]
          (is (some? (st/save-org s org)))
          (let [r (-> {:event {:build build
                               :sid [(:id org) (:id repo)]}}
                      (emi/set-db s)
                      (enter)
                      :event)]
            (is (some? (get-in r [:build :build-id])))
            (is (number? (get-in r [:build :idx])))
            (is (= [(:id org) (:id repo) (get-in r [:build :build-id])]
                   (:sid r)))))))))

(deftest save-build
  (h/with-memory-store s
    (let [{:keys [leave] :as i} sut/save-build
          build (-> (h/gen-build)
                    (assoc :script nil))
          get-sid (apply juxt st/build-sid-keys)]
      (is (keyword? (:name i)))

      (testing "`leave` saves build from result event"
        (is (= build (-> {:result {:build build}}
                         (emi/set-db s)
                         (leave)
                         (sut/get-build))))
        (is (= build (st/find-build s (get-sid build)))))

      (testing "does not overwrite jobs"
        (let [job {:id "test-job"
                   :type :container}]
          (is (some? (st/save-job s (get-sid build) job)))
          (is (some? (-> {:result
                          {:build
                           (assoc build 
                                  :script
                                  {:jobs {"other-job" {:id "other-job"}}})}}
                         (emi/set-db s)
                         (leave))))
          (is (= [job] (-> (st/find-build s (get-sid build))
                           :script
                           :jobs
                           vals))))))))

(deftest load-build
  (h/with-memory-store s
    (let [sid-keys [:org-id :repo-id :build-id]
          sid (repeatedly 3 cuid/random-cuid)
          build (-> (h/gen-build)
                    (merge (zipmap sid-keys sid)))
          {:keys [enter] :as i} sut/load-build]
      (is (some? (st/save-build s build)))
      (is (some? (:name i)))
      
      (testing "`enter` reads build in context using sid"
        (is (= build (-> {:event {:sid sid}}
                         (emi/set-db s)
                         (enter)
                         (sut/get-build))))))))

(deftest with-build
  (h/with-memory-store s
    (let [sid-keys [:org-id :repo-id :build-id]
          sid (repeatedly 3 cuid/random-cuid)
          build (-> (h/gen-build)
                    (merge (zipmap sid-keys sid))
                    (update :script assoc :jobs {}))
          {:keys [enter leave] :as i} sut/with-build]
      (is (some? (st/save-build s build)))
      (is (keyword? (:name i)))
      
      (testing "`enter` reads build in context using sid"
        (is (= build (-> {:event {:sid sid}}
                         (emi/set-db s)
                         (enter)
                         (sut/get-build)))))

      (testing "`leave`"
        (testing "saves build from result event"
          (let [upd (assoc build :status :success)]
            (is (= upd (-> {:result {:build upd}}
                           (emi/set-db s)
                           (leave)
                           (sut/get-build))))
            (is (= upd (st/find-build s (sut/build->sid build))))))))))

(deftest create-jobs
  (h/with-memory-store s
    (let [{:keys [enter] :as i} sut/create-jobs]
      (is (keyword? (:name i)))

      (testing "`enter` saves all jobs in event in db"
        (let [sid (vec (repeatedly 3 cuid/random-cuid))
              job {:id "test-job"}
              build (zipmap st/build-sid-keys sid)]
          ;; Build must exist otherwise jobs won't be saved
          (is (some? (st/save-build s build)))
          (is (some? (-> {:event
                          {:type :script/start
                           :jobs [job]
                           :sid sid}}
                         (emi/set-db s)
                         (enter))))
          (is (some? (st/find-build s sid)))
          (is (= job (st/find-job s (conj sid (:id job))))))))))

(deftest load-job
  (h/with-memory-store s
    (let [job (h/gen-job)
          build (-> (h/gen-build)
                    (assoc-in [:script :jobs] {(:id job) job}))
          sid (sut/build->sid build)
          {:keys [enter] :as i} sut/load-job]
      (is (keyword? (:name i)))
      (is (some? (st/save-build s build)))

      (testing "retrieves job from db"
        (is (= job (-> {:event {:sid sid
                                :job-id (:id job)}}
                       (emi/set-db s)
                       (enter)
                       (sut/get-job))))))))

(deftest save-job
  (h/with-memory-store s
    (let [job (h/gen-job)
          build (-> (h/gen-build)
                    (assoc-in [:script :jobs] {}))
          sid (sut/build->sid build)
          {:keys [leave] :as i} sut/save-job
          evt {:event {:sid sid
                       :job-id (:id job)}}]
      (is (keyword? (:name i)))
      (is (some? (st/save-build s build)))

      (testing "`leave`"
        (testing "saves job from result in db"
          (is (= job (-> evt
                         (emi/set-db s)
                         (em/set-result {:build (assoc-in build [:script :jobs (:id job)] job)})
                         (leave)
                         (sut/get-job))))
          (is (= job (st/find-job s (concat sid [(:id job)])))))

        (testing "creates job event"
          (is (= 1 (count (st/list-job-events s (conj (b/sid build) (:id job)))))))))))

(deftest save-credit-consumption
  (let [{:keys [leave] :as i} sut/save-credit-consumption]
    (is (keyword? (:name i)))

    (h/with-memory-store s
      (testing "inserts credit consumption in storage"
        (let [build (-> (h/gen-build)
                        (assoc :credits 100))
              ctx (-> {:result {:build build}}
                      (emi/set-db s))]
          (is (some? (st/save-org-credit s {:org-id (:org-id build)
                                            :amount 1000
                                            :type :user})))
          (is (= build (-> (leave ctx) :result :build)))
          (is (= 1 (count (st/list-org-credit-consumptions s (:org-id build)))))))

      (testing "when no credits, does not inserts credit consumption"
        (let [build (-> (h/gen-build)
                        (assoc :credits 100))
              ctx (-> {:result {:build build}}
                      (emi/set-db s))]
          (is (= build (-> (leave ctx) :result :build)))
          (is (empty? (st/list-org-credit-consumptions s (:org-id build)))))))))

(deftest check-credits
  (let [build (h/gen-build)
        ctx {:event {:type :build/triggered
                     :build build}}]
    
    (testing "returns `build/pending` event if credits available"
      (let [res (-> ctx
                    (sut/set-credits 100M)
                    (sut/check-credits))]
        (validate-spec :entity/build (:build res))
        (is (= :build/pending (:type res)))))

    (testing "returns `build/end` event with status `error` if no credits available"
      (let [r (sut/check-credits ctx)]
        (is (= :build/end (:type r)))
        (is (= :error (:status r)))))))

(deftest queue-build
  (testing "returns `build/queued` event"
    (let [build (h/gen-build)]
      (is (= :build/queued (-> {:event {:type :build/pending
                                        :sid (sut/build->sid build)
                                        :build build}}
                               (sut/queue-build)
                               :type))))))

(deftest build-initializing
  (let [build (h/gen-build)
        r (-> {:event {:type :build/initializing
                       :sid (sut/build->sid build)
                       :build {}}}
              (sut/set-build build)
              (sut/build-initializing))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "marks build as initializing"
      (is (= :initializing (get-in r [:build :status]))))))

(deftest build-start
  (let [build (h/gen-build)
        r (-> {:event {:type :build/start
                       :sid (sut/build->sid build)
                       :time 100
                       :credit-multiplier 3}}
              (sut/set-build build)
              (sut/build-start))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "marks build as running"
      (is (= :running (get-in r [:build :status]))))

    (testing "sets credit multiplier"
      (is (= 3 (get-in r [:build :credit-multiplier]))))

    (testing "sets start time"
      (is (= 100 (get-in r [:build :start-time]))))))

(deftest build-end
  (let [build (h/gen-build)
        r (-> {:event {:type :build/end
                       :sid (sut/build->sid build)
                       :time 100
                       :status :success}}
              (sut/set-build build)
              (sut/build-end))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "calculates consumed credits"
      (is (number? (get-in r [:build :credits]))))

    (testing "updates build status according to event"
      (is (= :success (get-in r [:build :status]))))

    (testing "sets end time"
      (is (= 100 (get-in r [:build :end-time]))))))

(deftest build-canceled
  (let [build (h/gen-build)
        r (-> {:event {:type :build/canceled
                       :sid (sut/build->sid build)
                       :time 100}}
              (sut/set-build build)
              (sut/build-canceled))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "calculates consumed credits"
      (is (number? (get-in r [:build :credits]))))

    (testing "updates build status"
      (is (= :canceled (get-in r [:build :status]))))

    (testing "sets end time"
      (is (= 100 (get-in r [:build :end-time]))))))

(deftest script-init
  (let [build (-> (h/gen-build)
                  (dissoc :script))
        r (-> {:event {:type :script/initializing
                       :sid (sut/build->sid build)
                       :script-dir "test/dir"}}
              (sut/set-build build)
              (sut/script-init))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))
    
    (testing "sets script dir in build"
      (is (= "test/dir" (get-in r [:build :script :script-dir]))))))

(deftest script-start
  (let [build (-> (h/gen-build)
                  (update :script dissoc :jobs))
        r (-> {:event {:type :script/start
                       :sid (sut/build->sid build)
                       :jobs [{:id ::test-job}]}}
              (sut/set-build build)
              (sut/script-start))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "adds jobs to build"
      (is (= {::test-job {:id ::test-job}}
             (get-in r [:build :script :jobs]))))))

(deftest script-end
  (let [build (-> (h/gen-build)
                  (update :script dissoc :jobs))
        r (-> {:event {:type :script/end
                       :sid (sut/build->sid build)
                       :status :success
                       :message "everything ok"}}
              (sut/set-build build)
              (sut/script-end))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "sets script status"
      (is (= :success
             (get-in r [:build :script :status]))))

    (testing "sets build message"
      (is (= "everything ok"
             (get-in r [:build :message]))))))

(deftest job-init
  (let [job (-> (h/gen-job)
                (assoc :status :pending))
        build (-> (h/gen-build)
                  (assoc-in [:script :jobs] {(:id job) job}))
        r (-> {:event {:type :job/initializing
                       :sid (sut/build->sid build)
                       :job-id (:id job)
                       :credit-multiplier 3}}
              (sut/set-build build)
              (sut/set-job job)
              (sut/job-init))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (testing "marks job initializing"
      (is (= :initializing
             (get-in r [:build :script :jobs (:id job) :status]))))

    (testing "sets job credit multiplier"
      (is (= 3 (get-in r [:build :script :jobs (:id job) :credit-multiplier]))))))

(deftest job-start
  (let [job (-> (h/gen-job)
                (assoc :status :initializing))
        build (-> (h/gen-build)
                  (assoc-in [:script :jobs] {(:id job) job}))
        r (-> {:event {:type :job/start
                       :sid (sut/build->sid build)
                       :job-id (:id job)
                       :credit-multiplier 3
                       :time 1000}}
              (sut/set-build build)
              (sut/set-job job)
              (sut/job-start))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (let [res (get-in r [:build :script :jobs (:id job)])]
      (testing "marks job as running"
        (is (= :running (:status res))))
      
      (testing "sets start time"
        (is (= 1000 (:start-time res))))
      
      (testing "sets credit multiplier if specified"
        (is (= 3 (:credit-multiplier res)))))))

(deftest job-end
  (let [job (-> (h/gen-job)
                (assoc :status :running))
        build (-> (h/gen-build)
                  (assoc-in [:script :jobs] {(:id job) job}))
        r (-> {:event {:type :job/end
                       :sid (sut/build->sid build)
                       :job-id (:id job)
                       :status :error
                       :result ::test-result
                       :time 1000}}
              (sut/set-build build)
              (sut/set-job job)
              (sut/job-end))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (let [res (get-in r [:build :script :jobs (:id job)])]
      (testing "sets job status"
        (is (= :error (:status res))))
      
      (testing "sets end time"
        (is (= 1000 (:end-time res))))
      
      (testing "sets result if specified"
        (is (= ::test-result (:result res)))))))

(deftest job-skipped
  (let [job (-> (h/gen-job)
                (assoc :status :running))
        build (-> (h/gen-build)
                  (assoc-in [:script :jobs] {(:id job) job}))
        r (-> {:event {:type :job/skipped
                       :sid (sut/build->sid build)
                       :job-id (:id job)}}
              (sut/set-build build)
              (sut/set-job job)
              (sut/job-skipped))]
    (testing "returns `build/updated` event"
      (validate-spec ::se/event r)
      (is (= :build/updated (:type r))))

    (let [res (get-in r [:build :script :jobs (:id job)])]
      (testing "marks job skipped"
        (is (= :skipped (:status res)))))))

(deftest routes
  (let [routes (->> (sut/make-routes (st/make-memory-storage)
                                     (mb/event-bus))
                    (into {}))
        event-types [:build/triggered
                     :build/pending
                     :build/initializing
                     :build/start
                     :build/end
                     :build/canceled
                     :script/initializing
                     :script/start
                     :script/end
                     :job/initializing
                     :job/start
                     :job/end
                     :job/skipped]]
    (doseq [t event-types]
      (testing (format "`%s` is handled" (str t))
        (is (contains? routes t))))))

(deftest router
  (let [st (st/make-memory-storage)
        router (em/make-router (sut/make-routes st (mb/event-bus)))]

    ;; We could also just test if the necessary interceptors have been
    ;; provided for each route
    
    (testing "`build/triggered`"
      (testing "with available credits"
        (let [org (h/gen-org)
              repo (-> (h/gen-repo)
                       (assoc :org-id (:id org)))
              creds {:id (cuid/random-cuid)
                     :org-id (:id org)
                     :amount 100M}]
          (is (some? (st/save-org st org)))
          (is (some? (st/save-org-credit st creds)))
          (is (some? (st/save-repo st repo)))
          
          (let [res (-> {:type :build/triggered
                         :build (-> (h/gen-build)
                                    (assoc :org-id (:id org)
                                           :repo-id (:id repo))
                                    ;; At this point, builds don't have an index
                                    (dissoc :idx :build-id))}
                        (router)
                        first
                        :result)]
            (testing "results in `build/pending` event"
              (validate-spec ::se/event (first res))
              (is (= :build/pending
                     (-> res first :type)))
              (let [{:keys [build sid]} (first res)]
                (is (= 3 (count sid)))
                (is (= (:build-id build) (last sid)))))

            (testing "saves build in db"
              (let [b (st/find-build st (-> res first :sid))]
                (is (some? b))
                (is (number? (:idx b)) "build has an index assigned"))))))

      (testing "when no credits"
        (let [org (h/gen-org)
              build (-> (h/gen-build)
                        (assoc :org-id (:id org))
                        (dissoc :build-id))]
          (is (some? (st/save-org st org)))

          (let [[res] (-> {:type :build/triggered
                           :build build
                           :sid [(:id org) (:repo-id build)]}
                          (router)
                          first
                          :result)]
            
            (testing "results in `build/end` event"
              (is (= :build/end (-> res :type)))
              (is (= :error (-> res :status))))

            (testing "saves build in db"
              (is (some? (st/find-build st (-> res :sid)))))))))

    (testing "`build/end`"
      (let [job {:id "test-job"
                 :start-time 1000
                 :end-time 2000
                 :status :success
                 :credit-multiplier 1}
            repo (h/gen-repo)
            org (-> (h/gen-org)
                    (assoc :repos {(:id repo) repo}))
            build (-> (h/gen-build)
                      (assoc :org-id (:id org)
                             :repo-id (:id repo)
                             :credit-multiplier 1
                             :start-time 1000
                             :script
                             {:jobs {(:id job) job}}))]
        (is (some? (st/save-org st org)))
        (is (some? (st/save-repo st repo)))
        (is (some? (st/save-build st build)))
        (is (some? (st/save-org-credit st {:org-id (:id org)
                                           :amount 100
                                           :type :user})))

        (let [res (-> {:type :build/end
                       :build build
                       :time 2000
                       :sid (sut/build->sid build)}
                      (router)
                      first
                      :result)
              match (st/find-build st (-> res first :sid))]
          (is (= (:id build) (:id match)))
          
          (testing "creates credit consumption"
            (let [cc (st/list-org-credit-consumptions st (:id org))]
              (is (= 1 (count cc)))
              (is (= 1 (:amount (first cc))))))

          (testing "fires `build/updated` event with jobs"
            (let [evt (first res)]
              (is (= :build/updated (:type evt)))
              (is (= 1 (-> evt :build :script :jobs count))))))))))
