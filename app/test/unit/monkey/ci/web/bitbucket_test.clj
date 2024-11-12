(ns monkey.ci.web.bitbucket-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [buddy.sign.jwt :as jwt]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.spec.build :as sb]
            [monkey.ci.web
             [auth :as auth]
             [bitbucket :as sut]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test
             [aleph-test :as at]
             [runtime :as trt]]))

(deftest login
  (testing "when token request fails, returns 400 status"
    (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                        {:status 401}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status)))))

  (testing "generates new token and returns it"
    (let [user-id (str (random-uuid))]
      (at/with-fake-http ["https://bitbucket.org/site/oauth2/access_token"
                          {:status 200
                           :body (h/to-json {:access-token "test-token"})
                           :headers {"Content-Type" "application/json"}}
                          "https://api.bitbucket.org/2.0/user"
                          {:status 200
                           :body (h/to-json {:uuid user-id})
                           :headers {"Content-Type" "application/json"}}]
        (let [kp (auth/generate-keypair)
              req (-> (trt/test-runtime)
                      (assoc :jwk {:priv (.getPrivate kp)})
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              token (-> req
                        (sut/login)
                        :body
                        :token)]
          (is (string? token))
          (let [u (jwt/unsign token (.getPublic kp) {:alg :rs256})]
            (is (map? u))
            (is (= (str "bitbucket/" user-id) (:sub u)))))))))

(deftest watch-repo
  (h/with-memory-store st
    (let [cust (h/gen-cust)
          ws "test-workspace"
          slug "test-bb-repo"
          bb-uuid (str (random-uuid))
          bb-requests (atom [])]
      (at/with-fake-http [{:url (format "https://api.bitbucket.org/2.0/repositories/%s/%s/hooks"
                                        ws slug)
                           :request-method :post}
                          (fn [req]
                            (if (some? (get-in req [:headers "Authorization"]))
                              (do
                                (swap! bb-requests conj (h/parse-json (:body req)))
                                {:status 201
                                 :headers {"Content-Type" "application/json"}
                                 :body (h/to-json {:uuid bb-uuid})})
                              {:status 401}))]
        (is (some? (st/save-customer st cust)))
        (let [r (-> {:storage st}
                    (h/->req)
                    (assoc :uri "/customer/test-cust"
                           :scheme :http
                           :headers {"host" "localhost"}
                           :parameters
                           {:path
                            {:customer-id (:id cust)}
                            :body
                            {:customer-id (:id cust)
                             :workspace ws
                             :repo-slug slug
                             :url "http://bitbucket.org/test-repo"
                             :name "test repo"
                             :token "test-bb-token"}})
                    (sut/watch-repo))]
          (is (= 201 (:status r)))
          
          (testing "creates repo in db"
            (is (some? (st/find-repo st [(:id cust) (get-in r [:body :id])]))))

          (let [whs (->> (p/list-obj st (st/webhook-sid))
                         (map (partial st/find-webhook st)))
                wh (->> whs
                        (filter (comp (partial = (get-in r [:body :id])) :repo-id))
                        (first))]
            (testing "creates webhook in db"
              (is (not-empty whs))
              (is (some? wh))
              (is (string? (:secret-key wh))))
            
            (testing "creates bitbucket webhook in repo"
              (let [bb-ws (->> (p/list-obj st (st/bb-webhook-sid))
                               (map (partial st/find-bb-webhook st)))
                    bb-wh (->> bb-ws
                               (filter (comp (partial = (:id wh)) :webhook-id))
                               (first))]
                (is (not-empty bb-ws))
                (is (some? bb-wh))
                (is (= bb-uuid (:bitbucket-id bb-wh)))
                (is (= 1 (count @bb-requests)))
                (is (= (str "http://localhost/webhook/bitbucket/" (:id wh))
                       (:url (first @bb-requests))))
                (is (= slug (:repo-slug bb-wh)))
                (is (= ws (:workspace bb-wh)))))))))

    (testing "404 if customer not found"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc-in [:parameters :path :customer-id] "invalid-customer")
                     (assoc :scheme :http
                            :headers {"host" "localhost"}
                            :uri "/customer")
                     (sut/watch-repo)
                     :status))))))

(deftest webhook
  (let [rt (trt/test-runtime)]
    (testing "triggers build for webhook"
      (let [runs (atom [])
            rt (-> rt
                   (trt/set-runner (fn [build _]
                                     (swap! runs conj build))))
            repo (-> (h/gen-repo)
                     (assoc :url "http://test-url"))
            cust (-> (h/gen-cust)
                     (assoc :repos {(:id repo) repo}))
            _ (st/save-customer (:storage rt) cust)
            wh {:id (cuid/random-cuid)
                :repo-id (:id repo)
                :customer-id (:id cust)}
            _ (st/save-webhook (:storage rt) wh)
            resp (-> rt
                     (h/->req)
                     (assoc :headers {"x-event-key" "repo:push"}
                            :parameters
                            {:path
                             {:id (:id wh)}})
                     (sut/webhook))]
        (is (= 202 (:status resp)))
        (is (re-matches #"^build-\d+$" (get-in resp [:body :build-id])))
        (is (not= :timeout (h/wait-until #(not-empty @runs) 500)))
        (let [build (first @runs)]
          (is (spec/valid? ::sb/build build)
              (spec/explain-str ::sb/build build))
          (is (some? (:git build)) "contains git info"))))

    (testing "404 if webhook does not exist"
      (is (= 404 (-> rt
                     (h/->req)
                     (assoc :headers {"x-event-key" "repo:push"}
                            :parameters
                            {:path
                             {:id (cuid/random-cuid)}})
                     (sut/webhook)
                     :status))))

    (testing "ignoring non-push events"
      (is (= 200 (-> rt
                     (h/->req)
                     (assoc :headers {"x-event-key" "some:other"})
                     (sut/webhook)
                     :status))))))
