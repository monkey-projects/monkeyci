(ns monkey.ci.web.bitbucket-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.sign.jwt :as jwt]
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
