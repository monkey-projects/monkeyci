(ns monkey.ci.web.api-test
  (:require [clojure.spec.alpha :as spec]
            [clojure.test :refer [deftest is testing]]
            [manifold
             [bus :as mb]
             [stream :as ms]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.spec.events :as se]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web
             [api :as sut]
             [response :as r]]))

(defn- parse-edn [s]
  (with-open [r (java.io.StringReader. s)]
    (u/parse-edn r)))

(defn- parse-event [evt]
  (let [prefix "data: "]
    (if (.startsWith evt prefix)
      (parse-edn (subs evt (count prefix)))
      (throw (ex-info "Invalid event payload" {:event evt})))))

(deftest create-repo
  (let [repo {:name "Test repo"
              :customer-id (st/new-id)}
        {st :storage :as rt} (trt/test-runtime)]
    
    (testing "generates id from repo name"
      (let [r (-> rt
                  (h/->req)
                  (h/with-body repo)
                  (sut/create-repo)
                  :body)]
        (is (= "test-repo" (:id r)))))

    (testing "on id collision, appends index"
      (let [new-repo {:name "Test repo"
                      :customer-id (:customer-id repo)}
            r (-> rt
                  (h/->req)
                  (h/with-body new-repo)
                  (sut/create-repo)
                  :body)]
        (is (= "test-repo-2" (:id r)))))))

(deftest create-webhook
  (testing "assigns secret key"
    (let [{st :storage :as rt} (trt/test-runtime)
          r (-> rt
                (h/->req)
                (h/with-body {:customer-id "test-customer"})
                (sut/create-webhook))]
      (is (= 201 (:status r)))
      (is (string? (get-in r [:body :secret-key]))))))

(deftest get-webhook
  (testing "does not return the secret key"
    (let [{st :storage :as rt} (trt/test-runtime)
          wh {:id (st/new-id)
              :secret-key "very secret key"}
          _ (st/save-webhook st wh)
          r (-> rt
                (h/->req)
                (h/with-path-param :webhook-id (:id wh))
                (sut/get-webhook))]
      (is (= 200 (:status r)))
      (is (nil? (get-in r [:body :secret-key]))))))

(deftest get-latest-build
  (testing "returns latest build"
    (let [{st :storage :as rt} (trt/test-runtime)
          md {:customer-id "test-cust"
              :repo-id "test-repo"}
          create-build (fn [ts]
                         (let [id (str "build-" ts)
                               sid (st/->sid (concat (vals md) [id]))]
                           (st/save-build st (merge md {:build-id id
                                                        :timestamp ts
                                                        :jobs []}))))
          _ (create-build 200)
          _ (create-build 100)
          r (-> rt
                (h/->req)
                (h/with-path-params md)
                (sut/get-latest-build))]
      (is (= "build-200" (-> r :body :build-id))))))

(deftest get-build
  (testing "retrieves build by id"
    (let [{st :storage :as rt} (trt/test-runtime)
          id (st/new-id)
          build {:customer-id "test-cust"
                 :repo-id "test-repo"
                 :build-id id
                 :status :success}
          sid (st/ext-build-sid build)
          _ (st/save-build st build)
          r (-> rt
                (h/->req)
                (h/with-path-params build)
                (sut/get-build))]
      (is (= 200 (:status r)))
      (is (= build (:body r))))))

(deftest build->out
  (testing "regular build"
    (testing "converts jobs to sequential"
      (is (sequential? (-> (sut/build->out {:script {:jobs {"test-job" {:id "test-job"}}}})
                           :script
                           :jobs))))

    (testing "assigns ids to jobs when none in job"
      (is (= "test-job"
             (-> (sut/build->out {:script {:jobs {"test-job" {:status :success}}}})
                 :script
                 :jobs
                 first
                 :id))))

    (testing "contains other properties"
      (let [build {:customer-id "test-cust"
                   :status :success}]
        (is (= build (sut/build->out build))))))

  (testing "legacy build"
    (testing "converts jobs"
      (is (= [{:id "test-job"}]
             (-> {:legacy? true
                  :jobs {"test-job" {:id "test-job"}}}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "converts pipelines with jobs"
      (is (= [{:id "test-job"
               :labels {"pipeline" "test-pipeline"}}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :jobs {0 {:name "test-job"}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "converts pipelines with steps"
      (is (= [{:id "test-job"
               :labels {"pipeline" "test-pipeline"}}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :steps {0 {:name "test-job"}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "assigns id to job"
      (is (= [{:id "test-pipeline-0"
               :labels {"pipeline" "test-pipeline"}
               :status :success}]
             (-> {:pipelines {0
                              {:name "test-pipeline"
                               :jobs {0 {:status :success}}}}
                  :legacy? true}
                 (sut/build->out)
                 :script
                 :jobs))))

    (testing "renames `timestamp` to `start-time`"
      (let [ts (u/now)]
        (is (= ts (-> {:legacy? true
                       :timestamp ts}
                      (sut/build->out)
                      :start-time)))))

    (testing "renames `result` to `status`"
      (is (= :success
             (-> {:legacy? true
                  :result :success}
                 (sut/build->out)
                 :status))))))

(defn- publish [bus evt]
  (deref (mb/publish! bus (:type evt) evt) 1000 :timeout))

(deftest event-stream
  (testing "returns stream reply"
    (is (ms/source? (-> (sut/event-stream (h/->req {:update-bus (mb/event-bus)}))
                        :body))))

  (testing "sends ping on open"
    (let [sent (atom [])
          bus (mb/event-bus)
          f (sut/event-stream (h/->req {:update-bus bus}))
          _ (ms/consume (partial swap! sent conj) (:body f))
          evt {:type :build/updated}]
      (is (true? (publish bus evt)))
      (is (not= :timeout
                (h/wait-until #(contains? (->> @sent
                                               (map parse-event)
                                               (map :type)
                                               (set))
                                          :ping)
                              1000)))))

  (testing "only sends events for customer specified in path"
    (let [sent (atom [])
          cid "test-customer"
          bus (mb/event-bus)
          f (-> (h/->req {:update-bus bus})
                (assoc-in [:parameters :path :customer-id] cid)
                (sut/event-stream))
          _ (ms/consume (fn [evt]
                          (swap! sent conj (parse-event evt)))
                        (:body f))]
      (is (true? (publish bus {:type :build/updated
                               :sid ["other-customer" "test-repo" "test-build"]})))
      (is (true? (publish bus {:type :build/updated
                               :sid [cid "test-repo" "test-build"]})))
      (is (not= :timeout
                (h/wait-until #(some (comp (partial = "test-customer")
                                           first
                                           :sid)
                                     @sent)
                              1000)))))

  (testing "sets `x-accel-buffering` header for nginx proxying"
    (let [r (-> (h/->req {:update-bus (mb/event-bus)})
                (sut/event-stream))]
      (is (= "no" (get-in r [:headers "x-accel-buffering"]))))))

(deftest make-build-ctx
  (testing "adds ref to build from branch query param"
    (is (= "refs/heads/test-branch"
           (-> (trt/test-runtime)
               (h/->req)
               (assoc-in [:parameters :query :branch] "test-branch")
               (sut/make-build-ctx {})
               :git
               :ref))))

  (testing "adds ref to build from tag query param"
    (is (= "refs/tags/test-tag"
           (-> (trt/test-runtime)
               (h/->req)
               (assoc-in [:parameters :query :tag] "test-tag")
               (sut/make-build-ctx {})
               :git
               :ref))))

  (testing "adds tag to build"
    (is (= "test-tag"
           (-> (trt/test-runtime)
               (h/->req)
               (assoc-in [:parameters :query :tag] "test-tag")
               (sut/make-build-ctx {})
               :git
               :tag))))

  (testing "adds configured encrypted ssh keys"
    (let [{st :storage :as rt} (trt/test-runtime)
          [cid rid] (repeatedly st/new-id)
          repo {:id rid
                :customer-id cid}
          ssh-key {:private-key "enc-private-key"
                   :public-key "public-key"}]
      (is (st/sid? (st/save-customer st {:id cid
                                         :repos {rid repo}})))
      (is (st/sid? (st/save-ssh-keys st cid [ssh-key])))
      (is (= [ssh-key]
             (-> (h/->req rt)
                 (assoc-in [:parameters :path] {:customer-id cid
                                                :repo-id rid})
                 (sut/make-build-ctx repo)
                 :git
                 :ssh-keys)))))

  (testing "adds main branch from repo"
    (is (= "test-branch"
           (-> (trt/test-runtime)
               (h/->req)
               (sut/make-build-ctx {:main-branch "test-branch"})
               :git
               :main-branch))))

  (testing "when no branch or tag specified, uses default branch"
    (is (= "refs/heads/main"
           (-> (trt/test-runtime)
               (h/->req)
               (sut/make-build-ctx {:main-branch "main"})
               :git
               :ref)))))

(deftest update-user
  (testing "updates user in storage"
    (let [{st :storage :as rt} (trt/test-runtime)]
      (is (st/sid? (st/save-user st {:type "github"
                                     :type-id 543
                                     :name "test user"})))
      (is (= 200 (-> (h/->req rt)
                     (assoc :parameters {:path
                                         {:user-type "github"
                                          :type-id 543}
                                         :body
                                         {:name "updated user"}})
                     (sut/update-user)
                     :status)))
      (is (= "updated user" (-> (st/find-user-by-type st [:github 543])
                                :name))))))

(deftest create-email-registration
  (testing "does not create same email twice"
    (let [{st :storage :as rt} (trt/test-runtime)
          email "duplicate@monkeyci.com"
          req (-> rt
                  (h/->req)
                  (h/with-body {:email email}))]
      (is (some? (st/save-email-registration st {:email email})))
      (is (= 200 (:status (sut/create-email-registration req))))
      (is (= 1 (count (st/list-email-registrations st)))))))

(deftest trigger-build
  (h/with-memory-store st
    (letfn [(with-repo [f]
              (let [cust-id (st/new-id)
                    repo (-> (h/gen-repo)
                             (assoc :customer-id cust-id))
                    cust (-> (h/gen-cust)
                             (assoc :id cust-id
                                    :repos {(:id repo) repo}))]
                (st/save-customer st cust)
                (st/save-customer-credit st {:customer-id cust-id
                                             :amount 1000})
                (f cust repo)))

            (make-rt []
              (-> (trt/test-runtime)
                  (trt/set-storage st)))

            (make-req [params]
              (-> (make-rt)
                  (h/->req)
                  (assoc :parameters params)))
            
            (verify-response [p f]
              (with-repo
                (fn [cust repo]
                  (let [rt (make-rt)
                        res (-> rt
                                (h/->req)
                                (assoc :parameters p)
                                (assoc-in [:parameters :path] {:customer-id (:id cust)
                                                               :repo-id (:id repo)})
                                (sut/trigger-build))]
                    (is (= 202 (:status res)))
                    (f (assoc rt
                              :sid [(:id cust) (:id repo)]
                              :response res))))))

            (verify-trigger [p f]
              (verify-response
               p
               (fn [{:keys [response]}]
                 (-> response
                     (r/get-events)
                     first
                     (f)))))]
      
      (testing "posts `build/triggered` event"
        (verify-response
         {}
         (fn [{:keys [response]}]
           (is (= :build/triggered
                  (-> response
                      (r/get-events)
                      first
                      :type))))))
      
      (testing "looks up url in repo config"
        (with-repo
          (fn [{customer-id :id} {repo-id :id}]
            (is (some? (st/save-customer st {:id customer-id
                                             :repos
                                             {repo-id
                                              {:id repo-id
                                               :url "http://test-url"}}})))
            (is (= "http://test-url"
                   (-> (make-req {:path {:customer-id customer-id
                                         :repo-id repo-id}})
                       (sut/trigger-build)
                       (r/get-events)
                       first
                       :build
                       :git
                       :url))))))
      
      (testing "adds commit id from query params"
        (verify-trigger
         {:query {:commit-id "test-id"}}
         (fn [{:keys [build]}]
           (is (= "test-id"
                  (-> build
                      :git
                      :commit-id))))))

      (testing "adds branch from query params as ref"
        (verify-trigger
         {:query {:branch "test-branch"}}
         (fn [{:keys [build]}]
           (is (= "refs/heads/test-branch"
                  (-> build :git :ref))))))

      (testing "adds tag from query params as ref"
        (verify-trigger
         {:query {:tag "test-tag"}}
         (fn [{:keys [build]}]
           (is (= "refs/tags/test-tag"
                  (-> build :git :ref))))))

      (testing "adds `sid` to event"
        (verify-response
         {}
         (fn [{:keys [sid response]}]
           (is (= 2 (count sid)) "expected sid to contain customer and repo id"))))
      
      (testing "does not create build in storage"
        (verify-trigger
         {:query {:branch "test-branch"}}
         (fn [evt]
           (let [bsid (:sid evt)
                 build (st/find-build st bsid)]
             (is (nil? build))))))

      (testing "assigns unique id"
        (verify-trigger
         {}
         (fn [evt]
           (is (cuid/cuid? (get-in evt [:build :id]))))))
      
      (testing "returns id"
        (with-repo
          (fn [cust repo]
            (is (cuid/cuid? (-> (make-req {:path {:customer-id (:id cust)
                                                  :repo-id (:id repo)}})
                                (sut/trigger-build)
                                :body
                                :id))))))

      (testing "returns 404 (not found) when repo does not exist"
        (is (= 404 (-> (make-req {:path {:customer-id "nonexisting"
                                         :repo-id "also-nonexisting"}})
                       (sut/trigger-build)
                       :status)))))))

(deftest retry-build
  (h/with-memory-store st
    (let [build (-> (h/gen-build)
                    (assoc :start-time 100
                           :end-time 200))
          make-req (fn [runner params]
                     (-> {:storage st
                          :runner runner}
                         (h/->req)
                         (assoc :parameters params)))]
      (is (some? (st/save-build st build)))

      (let [r (-> (make-req (constantly "ok")
                            {:path (select-keys build [:customer-id :repo-id :build-id])})
                  (sut/retry-build))
            bid (-> r :body :id)]
        (testing "returns newly created build id"
          (is (some? bid))
          (is (not= (:id build) bid)))
        
        (testing "creates new build with same settings but without script details"
          (let [new (-> r r/get-events first :build)]
            (is (some? new))
            (is (= :pending (:status new)))
            (is (= (:git build) (:git new)))
            (is (number? (:start-time new)))
            (is (nil? (:end-time new)))
            (is (nil? (:script new)))
            (is (empty? (get-in new [:script :jobs]))))))

      (testing "returns 404 if build not found"
        (is (= 404 (-> (make-req (constantly "ok")
                                 {:path (-> build
                                            (select-keys [:customer-id :repo-id])
                                            (assoc :build-id "non-existing"))})
                       (sut/retry-build)
                       :status)))))))

(deftest cancel-build
  (h/with-memory-store st
    (let [build (h/gen-build)
          make-req (fn [& [params]]
                     (-> {:storage st}
                         (h/->req)
                         (assoc :parameters
                                (merge {:path (select-keys build st/build-sid-keys)}
                                       params))))
          sid st/ext-build-sid]
      (is (some? (st/save-build st build)))
      
      (testing "dispatchs `build/canceled` event"
        (let [resp (-> (make-req)
                       (sut/cancel-build))
              [e :as evts] (r/get-events resp)]
          (is (= 202 (:status resp)))
          (is (= [:build/canceled] (map :type evts)))
          (is (spec/valid? ::se/event e))
          (is (= (sid build) (:sid e)))))
      
      (testing "404 if build not found"
        (let [resp (-> (make-req {:path {:build-id "non-existing"}})
                       (sut/cancel-build))]
          (is (= 404 (:status resp)))
          (is (empty? (r/get-events resp))))))))
