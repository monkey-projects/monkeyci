(ns monkey.ci.web.codeberg-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.math :as cm]
            [buddy.sign.jwt :as jwt]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.web
             [auth :as auth]
             [codeberg :as sut]]
            [monkey.ci.test
             [aleph-test :as af]
             [helpers :as h]
             [runtime :as trt]]))

(defn- with-codeberg-user
  "Sets up fake http communication with codeberg to return the given user"
  ([u f]
   (af/with-fake-http [{:url "https://codeberg.org/login/oauth/access_token"
                        :request-method :post}
                       {:status 200
                        :body (h/to-json {:access-token "test-token"})
                        :headers {"Content-Type" "application/json"}}
                       {:url "https://codeberg.org/api/v1/user"
                        :request-method :get}
                       {:status 200
                        :body (h/to-json u)
                        :headers {"Content-Type" "application/json"}}]
     (f u)))
  ([f]
   (with-codeberg-user {:login "test user"
                        :id (int (* (cm/random) 10000))}
     f)))

(deftest login
  (testing "when exchange fails at codeberg, returns body and 400 status code"
    (af/with-fake-http ["https://codeberg.org/login/oauth/access_token"
                        {:status 401
                         :body (h/to-json {:message "invalid access code"})
                         :headers {"Content-Type" "application/json"}}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status)))))

  (testing "generates new token and returns it"
    (with-codeberg-user
      (fn [_]
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
            (is (re-matches #"^codeberg/.*" (:sub u))))))))

  (testing "finds existing codeberg user in storage"
    (with-codeberg-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              _ (st/save-user st {:type "codeberg"
                                  :type-id (:id u)
                                  :orgs ["test-org"]})
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))]
          (is (= ["test-org"]
                 (-> req
                     (sut/login)
                     :body
                     :orgs)))))))

  (testing "creates user when none found in storage"
    (with-codeberg-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))]
          (is (= 200
                 (-> req
                     (sut/login)
                     :status)))
          (let [c (st/find-user-by-type st [:codeberg (:id u)])]
            (is (some? c))
            (is (cuid/cuid? (:id c)) "user should have a cuid"))))))

  (testing "sets user id in token"
    (with-codeberg-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              pubkey (auth/rt->pub-key rt)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              _ (st/save-user st {:type "codeberg"
                                  :type-id (:id u)
                                  :id (st/new-id)})
              token (-> req
                        (sut/login)
                        :body
                        :token)]
          (is (string? token))
          (is (= (str "codeberg/" (:id u))
                 (-> token
                     (jwt/unsign pubkey {:alg :rs256})
                     :sub)))))))

  (testing "adds codeberg token to response"
    (with-codeberg-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              _ (st/save-user st {:type "codeberg"
                                  :type-id (:id u)
                                  :id (st/new-id)})
              resp (-> req
                       (sut/login)
                       :body)]
          (is (string? (:codeberg-token resp))))))))
