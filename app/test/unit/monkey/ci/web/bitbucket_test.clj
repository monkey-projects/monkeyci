(ns monkey.ci.web.bitbucket-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.sign.jwt :as jwt]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
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
                           :method :post}
                          (fn [req]
                            (swap! bb-requests conj (h/parse-json (:body req)))
                            {:status 201
                             :headers {"Content-Type" "application/json"}
                             :body (h/to-json {:uuid bb-uuid})})]
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
                             :name "test repo"}})
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
                       (:url (first @bb-requests))))))))))

    (testing "404 if customer not found"
      (is (= 404 (-> {:storage st}
                     (h/->req)
                     (assoc-in [:parameters :path :customer-id] "invalid-customer")
                     (assoc :scheme :http
                            :headers {"host" "localhost"}
                            :uri "/customer")
                     (sut/watch-repo)
                     :status))))))
