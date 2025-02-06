(ns monkey.ci.events.mailman-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [monkey.ci.events.mailman :as sut]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.spec
             [events :as se]
             [entities :as sen]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(defn- validate-spec [spec obj]
  (is (spec/valid? spec obj)
      (spec/explain-str spec obj)))

(deftest use-db
  (testing "adds db to context"
    (let [i (sut/use-db ::test-db)]
      (is (= ::sut/use-db (:name i)))
      (is (fn? (:enter i)))
      (is (= ::test-db (-> ((:enter i) {})
                           ::sut/db))))))

(deftest customer-credits
  (h/with-memory-store s
    (let [cust (h/gen-cust)]
      (is (some? (st/save-customer s cust)))
      (is (some? (st/save-customer-credit s {:customer-id (:id cust)
                                             :amount 100})))
      
      (testing "adds available customer credits to context"
        (let [{:keys [enter] :as i} sut/customer-credits]
          (is (= ::sut/customer-credits (:name i)))
          (is (fn? enter))
          (is (= 100M
                 (-> {:event {:build
                              {:customer-id (:id cust)}}}
                     (sut/set-db s)
                     (enter)
                     (sut/get-credits)))))))))

(deftest save-build
  (h/with-memory-store s
    (testing "`leave` saves build from result event"
      (let [{:keys [leave] :as i} sut/save-build
            build (h/gen-build)
            get-sid (apply juxt st/build-sid-keys)]
        (is (keyword? (:name i)))
        (is (= build (-> {:result {:build build}}
                         (sut/set-db s)
                         (leave)
                         (sut/get-build))))
        (is (= build (st/find-build s (get-sid build))))))))

(deftest load-build
  (h/with-memory-store s
    (let [sid-keys [:customer-id :repo-id :build-id]
          sid (repeatedly 3 cuid/random-cuid)
          build (-> (h/gen-build)
                    (merge (zipmap sid-keys sid)))
          {:keys [enter] :as i} sut/load-build]
      (is (some? (st/save-build s build)))
      (is (some? (:name i)))
      
      (testing "`enter` reads build in context using sid"
        (is (= build (-> {:event {:sid sid}}
                         (sut/set-db s)
                         (enter)
                         (sut/get-build))))))))

(deftest with-build
  (h/with-memory-store s
    (let [sid-keys [:customer-id :repo-id :build-id]
          sid (repeatedly 3 cuid/random-cuid)
          build (-> (h/gen-build)
                    (merge (zipmap sid-keys sid)))
          {:keys [enter leave] :as i} sut/with-build]
      (is (some? (st/save-build s build)))
      (is (keyword? (:name i)))
      
      (testing "`enter` reads build in context using sid"
        (is (= build (-> {:event {:sid sid}}
                         (sut/set-db s)
                         (enter)
                         (sut/get-build)))))

      (testing "`leave` saves build from result event"
        (let [upd (assoc build :status :success)]
          (is (= upd (-> {:result {:build upd}}
                         (sut/set-db s)
                         (leave)
                         (sut/get-build))))
          (is (= upd (st/find-build s (sut/build->sid build)))))))))

(deftest save-credit-consumption
  (let [{:keys [leave] :as i} sut/save-credit-consumption]
    (is (keyword? (:name i)))

    (h/with-memory-store s
      (testing "inserts credit consumption in storage"
        (let [build (-> (h/gen-build)
                        (assoc :credits 100))
              ctx (-> {:result {:build build}}
                      (sut/set-db s))]
          (is (some? (st/save-customer-credit s {:customer-id (:customer-id build)
                                                 :amount 1000
                                                 :type :user})))
          (is (= build (-> (leave ctx) :result :build)))
          (is (= 1 (count (st/list-customer-credit-consumptions s (:customer-id build)))))))

      (testing "when no credits, does not inserts credit consumption"
        (let [build (-> (h/gen-build)
                        (assoc :credits 100))
              ctx (-> {:result {:build build}}
                      (sut/set-db s))]
          (is (= build (-> (leave ctx) :result :build)))
          (is (empty? (st/list-customer-credit-consumptions s (:customer-id build)))))))))

(deftest add-time
  (let [{:keys [leave] :as i} sut/add-time]
    (is (keyword? (:name i)))
    
    (testing "sets event times"
      (is (number? (-> {:result [{:type ::test-event}]}
                       (leave)
                       :result
                       first
                       :time))))))

(deftest trace-evt
  (let [{:keys [enter leave] :as i} sut/trace-evt]
    (is (keyword? (:name i)))
    
    (testing "`enter` returns context as-is"
      (is (= ::test-ctx (enter ::test-ctx))))

    (testing "`leave` returns context as-is"
      (is (= ::test-ctx (leave ::test-ctx))))))

(deftest check-credits
  (let [build (h/gen-build)
        ctx {:event {:type :build/triggered
                     :build build}}]
    
    (testing "returns `build/pending` event if credits available"
      (let [res (-> ctx
                    (sut/set-credits 100M)
                    (sut/check-credits))]
        (validate-spec :entity/build (:build res))
        (is (= :pending (get-in res [:build :status])))
        (is (= :build/pending (:type res)))))

    (testing "returns `build/failed` event if no credits available"
      (is (= :build/failed (-> (sut/check-credits ctx)
                               :type))))))

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

    (testing "updates build status"
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

(deftest routes
  (let [routes (->> (sut/make-routes (trt/test-runtime))
                    (into {}))
        event-types [:build/triggered
                     :build/pending
                     :build/initializing
                     :build/start
                     :build/end
                     :build/canceled]]
    (doseq [t event-types]
      (testing (format "`%s` is handled" (str t))
        (is (contains? routes t))))))

(deftest router
  (let [{st :storage :as rt} (trt/test-runtime)
        router (sut/make-router rt)]

    ;; We could also just test if the necessary interceptors have been
    ;; provided for each route
    
    (testing "`build/triggered`"
      (testing "with available credits"
        (let [cust (h/gen-cust)
              creds {:id (cuid/random-cuid)
                     :customer-id (:id cust)
                     :amount 100M}]
          (is (some? (st/save-customer st cust)))
          (is (some? (st/save-customer-credit st creds)))
          
          (let [res (-> {:type :build/triggered
                         :build (-> (h/gen-build)
                                    (assoc :customer-id (:id cust)))}
                        (router)
                        first
                        :result)]
            (testing "results in `build/pending` event"
              (validate-spec ::se/event (first res))
              (is (= :build/pending
                     (-> res first :type))))

            (testing "saves build in db"
              (is (some? (st/find-build st (-> res first :sid))))))))

      (testing "when no credits"
        (let [cust (h/gen-cust)]
          (is (some? (st/save-customer st cust)))

          (let [res (-> {:type :build/triggered
                         :build (-> (h/gen-build)
                                    (assoc :customer-id (:id cust)))}
                        (router)
                        first
                        :result)]
            
            (testing "results in `build/failed` event"
              (is (= :build/failed (-> res first :type))))

            (testing "saves build in db"
              (is (some? (st/find-build st (-> res first :sid)))))))))

    (testing "`build/end`"
      (testing "creates credit consumption"
        (let [repo (h/gen-repo)
              cust (-> (h/gen-cust)
                       (assoc :repos {(:id repo) repo}))
              build (-> (h/gen-build)
                        (assoc :customer-id (:id cust)
                               :repo-id (:id repo)
                               :credit-multiplier 1
                               :credits 10))]
          (is (some? (st/save-customer st cust)))
          (is (some? (st/save-repo st repo)))
          (is (some? (st/save-build st build)))
          (is (some? (st/save-customer-credit st {:customer-id (:id cust)
                                                  :amount 100
                                                  :type :user})))

          (let [res (-> {:type :build/end
                         :build build
                         :sid (sut/build->sid build)}
                        (router)
                        first
                        :result)
                match (st/find-build st (-> res first :sid))
                cc (st/list-customer-credit-consumptions st (:id cust))]
            (is (some? match))
            (is (= 1 (count cc)))
            (is (= 10 (:amount (first cc))))))))))
