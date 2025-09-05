(ns monkey.ci.web.auth-test
  (:require [buddy.core.keys :as bk]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.auth :as sut]))

(deftest generate-secret-key
  (testing "generates random string"
    (is (string? (sut/generate-secret-key)))))

(deftest secure-ring-app
  (let [app :identity
        {st :storage :as rt} (trt/test-runtime)
        sec (sut/secure-ring-app app rt)
        req (h/->req rt)]

    (testing "with user token"
      (is (st/sid? (st/save-user st {:type "github"
                                     :type-id 456})))
      (is (nil? (sec {}))
          "no identity provided")
      
      (testing "verifies bearer token using public key and puts user in request `:identity`"
        (is (some? (sec (-> req
                            (assoc :headers
                                   {"authorization"
                                    (str "Bearer " (sut/generate-jwt req {:role sut/role-user
                                                                          :sub "github/456"}))}))))
            "bearer token provided"))

      (testing "verifies token query param using public key and puts user in request `:identity`"
        (is (some? (sec (-> req
                            (assoc-in [:query-params "authorization"]
                                      (sut/generate-jwt req (sut/user-token ["github" "456"]))))))
            "bearer token provided")))

    (testing "with build token, puts build org and repo in identity"
      (let [[cid rid bid :as sid] (repeatedly 3 cuid/random-cuid)]
        (is (st/sid? (st/save-org st {:id cid
                                      :name "test org"
                                      :repos [{:id rid
                                               :name "test repo"}]})))
        (is (st/sid? (st/save-build st {:org-id cid
                                        :repo-id rid
                                        :build-id bid})))
        (is (some? (-> req
                       (assoc :headers
                              {"authorization"
                               (str "Bearer " (sut/generate-jwt req (sut/build-token sid)))})
                       (sec)))
            "bearer token provided")))

    (testing "accepts sysadmin token"
      (let [[_ username :as sid] ["sysadmin" "test-admin"]]
        (is (st/sid? (st/save-user st
                                   {:id (cuid/random-cuid)
                                    :type-id username
                                    :type "sysadmin"})))
        (is (= username (-> req
                            (assoc :headers
                                   {"authorization"
                                    (str "Bearer " (sut/generate-jwt req (sut/sysadmin-token sid)))})
                            (sec)
                            :type-id))
            "bearer token provided")))))

(deftest auth-chain
  (testing "allows if chain is empty"
    (is (sut/allowed? (sut/auth-chain [] {}))))

  (testing "allows if all parts allow"
    (is (sut/allowed? (sut/auth-chain [(constantly nil)] {}))))

  (testing "denies if last denies"
    (is (sut/denied? (sut/auth-chain [(constantly nil)
                                      (constantly {:permission :denied})]
                                     {}))))

  (testing "allows if last allows"
    (is (sut/allowed? (sut/auth-chain [(constantly {:permission :denied})
                                       (constantly {:permission :granted})]
                                      {}))))

  (testing "passes request to checkers"
    (let [chain [(fn [_ req]
                   (when (= :post (:request-method req))
                     {:permission :denied}))]]
      (is (sut/allowed? (sut/auth-chain chain {:request-method :get})))
      (is (sut/denied? (sut/auth-chain chain {:request-method :post}))))))

(deftest auth-chain-middleware
  (let [target (constantly ::handled)
        w (sut/auth-chain-middleware target)]
    (testing "allows when no checkers"
      (is (= ::handled
             (w {}))))

    (testing "denies when chain denies"
      (is (thrown? Exception
                   (-> {:auth-chain [(constantly {:permission :denied})]}
                       (h/->match-data)
                       (w)))))

    (testing "allows when chain allows"
      (is (= ::handled
             (-> {:auth-chain [(constantly nil)]}
                 (h/->match-data)
                 (w)))))))

(deftest chain-result->exception
  (testing "`nil` if approved"
    (is (nil? (sut/chain-result->exception nil)))
    (is (nil? (sut/chain-result->exception {:permission :granted}))))

  (testing "if denied"
    (let [r (sut/chain-result->exception {:permission :denied
                                          :reason "For testing"
                                          ::key ::value})]
      (testing "sets reason as message"
        (is (= "For testing" (ex-message r))))

      (testing "sets type unauthorized"
        (is (= :auth/unauthorized
               (:type (ex-data r)))))

      (testing "adds additional values to exception"
        (is (= ::value (::key (ex-data r))))))))

(deftest public-repo-checker
  (h/with-memory-store st
    (let [org (h/gen-org)
          priv-repo (-> (h/gen-repo)
                        (assoc :org-id (:id org)))
          pub-repo (-> (h/gen-repo)
                       (assoc :org-id (:id org)
                              :public true))
          req (-> {:storage st}
                  (h/->req)
                  (assoc-in [:parameters :path :org-id] (:id org)))]
      (is (some? (st/save-org st org)))
      (is (some? (st/save-repo st priv-repo)))
      (is (some? (st/save-repo st pub-repo)))

      (testing "public repos"
        (testing "allows access for `GET` requests"
          (is (sut/allowed?
               (sut/public-repo-checker
                []
                (-> req
                    (assoc :request-method :get)
                    (assoc-in [:parameters :path :repo-id] (:id pub-repo)))))))

        (testing "allows access for `OPTIONS` requests"
          (is (sut/allowed?
               (sut/public-repo-checker
                []
                (-> req
                    (assoc :request-method :options)
                    (assoc-in [:parameters :path :repo-id] (:id pub-repo)))))))

        (testing "does not allow access for destructive requests"
          (is (sut/denied?
               (sut/public-repo-checker
                []
                (-> req
                    (assoc :request-method :post)
                    (assoc-in [:parameters :path :repo-id] (:id pub-repo))))))))

      (testing "does not allow access to private repos"
        (is (sut/denied?
             (sut/public-repo-checker
              []
              (assoc-in req [:parameters :path :repo-id] (:id priv-repo))))))

      (testing "allows non-repo requests"
        (is (nil? (sut/public-repo-checker [] req)))))))

(deftest org-auth-checker
  (testing "allows"
    (testing "if no org id in request path"
      (is (nil? (sut/org-auth-checker [] {}))))

    (testing "if identity allows access to org id"
      (is (nil? (sut/org-auth-checker
                 []
                 {:parameters
                  {:path
                   {:org-id "test-org"}}
                  :identity
                  {:orgs
                   #{"test-org"}}}))))

    (testing "if sysadmin token"
      (is (nil? (sut/org-auth-checker
                 []
                 {:parameters
                  {:path
                   {:org-id "test-org"}}
                  :identity
                  {:type :sysadmin}})))))

  (testing "denies"
    (testing "if org id is not in identity"
      (is (sut/denied?
           (sut/org-auth-checker
            []
            {:parameters
             {:path
              {:org-id "test-org"}}
             :identity
             {:orgs #{"other-org"}}}))))

    (testing "if not authenticated"
      (is (sut/denied?
           (sut/org-auth-checker
            []
            {:parameters
             {:path
              {:org-id "test-org"}}}))))))

(deftest sysadmin-authorization
  (let [h (constantly ::ok)
        auth (sut/sysadmin-authorization h)]
    (testing "invokes target"
      (testing "if identity is sysadmin"
        (is (= ::ok (auth {:identity
                           {:type :sysadmin}})))))

    (testing "throws authorization error"
      (testing "if user is not a sysadmin"
        (is (thrown? Exception
                     (auth {:identity
                            {:type :github}}))))

      (testing "if not authenticated"
        (is (thrown? Exception
                     (auth {})))))))

(deftest valid-security?
  (testing "false if nil"
    (is (not (true? (sut/valid-security? nil)))))

  (testing "true if valid"
    ;; Github provided values for testing
    (is (true? (sut/valid-security?
                {:secret "It's a Secret to Everybody"
                 :payload "Hello, World!"
                 :x-hub-signature "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"})))))

(deftest parse-signature
  (testing "nil if nil input"
    (is (nil? (sut/parse-signature nil))))

  (testing "returns signature and algorithm"
    (is (= {:alg :sha256
            :signature "test-value"}
           (sut/parse-signature "sha256=test-value")))))

(deftest sysadmin-token
  (testing "has `sysadmin` role"
    (is (= "sysadmin" (:role (sut/sysadmin-token ["test-admin-user"]))))))
