(ns monkey.ci.web.github-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.math :as cm]
            [buddy.sign.jwt :as jwt]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.web
             [auth :as auth]
             [github :as sut]]
            [monkey.ci.helpers :as h]
            [org.httpkit.fake :as hf]
            [ring.mock.request :as mock]))

(deftest valid-security?
  (testing "false if nil"
    (is (not (true? (sut/valid-security? nil)))))

  (testing "true if valid"
    ;; Github provided values for testing
    (is (true? (sut/valid-security?
                {:secret "It's a Secret to Everybody"
                 :payload "Hello, World!"
                 :x-hub-signature "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"})))))

(deftest extract-signature
  (testing "nil if nil input"
    (is (nil? (sut/extract-signature nil))))

  (testing "returns value of the sha256 key"
    (is (= "test-value" (sut/extract-signature "sha256=test-value"))))

  (testing "nil if key is not sha256"
    (is (nil? (sut/extract-signature "key=value")))))

(deftest webhook
  (testing "invokes runner from runtime"
    (let [inv (atom nil)
          st (st/make-memory-storage)
          _ (st/save-webhook-details st {:id "test-hook"})
          req (-> {:runner (partial swap! inv conj)
                   :storage st}
                  (h/->req)
                  (assoc :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}}}))]
      (is (= 200 (:status (sut/webhook req))))
      (is (not= :timeout (h/wait-until #(some? @inv) 1000)))))

  (testing "ignores non-commit events"
    (let [inv (atom nil)
          req (-> {:runner (partial swap! inv conj)}
                  (h/->req)
                  (assoc :parameters {:body 
                                      {:key "value"}}))]
      (is (= 204 (:status (sut/webhook req))))
      (is (nil? @inv))))

  (testing "fires build end event on failure"
    (let [st (st/make-memory-storage)
          _ (st/save-webhook-details st {:id "test-hook"})
          events (atom [])
          req (-> {:runner (fn [rt]
                             (throw (ex-info "Test error" {:runtime rt})))
                   :storage st
                   :events {:poster (partial swap! events conj)}}
                  (h/->req)
                  (assoc :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}}}))]
      (is (= 200 (:status (sut/webhook req))))
      (is (not= :timeout (h/wait-until #(not-empty @events) 1000)))
      (is (= :build/end (-> @events first :type))))))

(defn- test-webhook []
  (zipmap [:id :customer-id :repo-id] (repeatedly st/new-id)))

(deftest create-build
  (testing "creates build record for customer/repo"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {}})]
          (is (some? r))
          (is (p/obj-exists? s (-> wh
                                   (select-keys [:customer-id :repo-id])
                                   (assoc :build-id (:build-id r))
                                   (st/build-sid))))))))

  (testing "build contains commit message"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:head-commit {:message "test message"}}})
              id (:sid r)
              md (st/find-build s id)]
          (is (st/sid? id))
          (is (some? md))
          (is (= "test message" (get-in md [:git :message])))))))

  (testing "adds start time as current epoch millis"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:head-commit {:timestamp "2023-10-10"}}})
              id (:sid r)
              md (st/find-build s id)]
          (is (st/sid? id))
          (is (some? md))
          (is (number? (:start-time md)))))))
  
  (testing "`nil` if no configured webhook found"
    (h/with-memory-store s
      (is (nil? (sut/create-build {:storage s}
                                  {:id "test-webhook"
                                   :payload {}})))))

  (testing "uses clone url for public repos"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:repository {:ssh-url "ssh-url"
                                                          :clone-url "clone-url"}}})]
          (is (= "clone-url" (get-in r [:git :url])))))))

  (testing "uses ssh url if repo is private"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:repository {:ssh-url "ssh-url"
                                                          :clone-url "clone-url"
                                                          :private true}}})]
          (is (= "ssh-url" (get-in r [:git :url])))))))

  (testing "does not include secret key in metadata"
    (h/with-memory-store s
      (let [wh (assoc (test-webhook) :secret-key "very very secret!")]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:head-commit {:message "test message"
                                                           :timestamp "2023-10-10"}}})
              id (:sid r)
              md (st/find-build s id)]
          (is (not (contains? md :secret-key)))))))

  (testing "adds payload ref to build"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:ref "test-ref"}})]
          (is (= "test-ref" (get-in r [:git :ref])))))))

  (testing "sets `main-branch`"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:ref "test-ref"
                                             :repository {:master-branch "test-main"}}})]
          (is (= "test-main"
                 (get-in r [:git :main-branch])))))))

  (testing "sets `commit-id`"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:head-commit {:id "test-commit-id"}}})]
          (is (= "test-commit-id"
                 (get-in r [:git :commit-id])))))))

  (testing "adds configured ssh key matching repo labels"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:customer-id wh)
            rid (:repo-id wh)
            ssh-key {:id "test-key"
                     :private-key "test-ssh-key"}]
        (is (st/sid? (st/save-webhook-details s wh)))
        (is (st/sid? (st/save-repo s {:customer cid
                                      :id rid
                                      :labels [{:name "ssh-lbl"
                                                :value "lbl-val"}]})))
        (is (st/sid? (st/save-ssh-keys s cid [ssh-key])))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:ref "test-ref"
                                             :repository {:master-branch "test-main"}}})]
          (is (= [ssh-key]
                 (get-in r [:git :ssh-keys])))))))

  (testing "sets cleanup flag"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:customer-id wh)
            rid (:repo-id wh)]
        (is (st/sid? (st/save-webhook-details s wh)))
        (is (st/sid? (st/save-repo s {:customer cid
                                      :id rid})))
        (let [r (sut/create-build {:storage s}
                                  {:id (:id wh)
                                   :payload {:ref "test-ref"
                                             :repository {:master-branch "test-main"}}})]
          (is (true? (:cleanup? r))))))))

(defn- with-github-user
  "Sets up fake http communication with github to return the given user"
  ([u f]
   (hf/with-fake-http ["https://github.com/login/oauth/access_token"
                       {:status 200
                        :body (h/to-json {:access-token "test-token"})}
                       "https://api.github.com/user"
                       {:status 200
                        :body (h/to-json u)}]
     (f u)))
  ([f]
   (with-github-user {:name "test user"
                      :id (int (* (cm/random) 10000))}
     f)))

(deftest login
  (testing "when exchange fails at github, returns body and 400 status code"
    (hf/with-fake-http ["https://github.com/login/oauth/access_token"
                        {:status 401
                         :body (h/to-json {:message "invalid access code"})}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status)))))

  (testing "generates new token and returns it"
    (with-github-user
      (fn [_]
        (let [kp (auth/generate-keypair)
              req (-> (h/test-rt)
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
          (is (map? (jwt/unsign token (.getPublic kp) {:alg :rs256})))))))

  (testing "finds existing github user in storage"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (h/test-rt)
              _ (st/save-user st {:type "github"
                                  :type-id (:id u)
                                  :customers ["test-cust"]})
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))]
          (is (= ["test-cust"]
                 (-> req
                     (sut/login)
                     :body
                     :customers)))))))

  (testing "creates user when none found in storage"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (h/test-rt)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))]
          (is (= 200
                 (-> req
                     (sut/login)
                     :status)))
          (is (some? (st/find-user st [:github (:id u)])))))))

  (testing "sets user id in token"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (h/test-rt)
              pubkey (auth/rt->pub-key rt)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              _ (st/save-user st {:type "github"
                                  :type-id (:id u)
                                  :id (st/new-id)})
              token (-> req
                        (sut/login)
                        :body
                        :token)]
          (is (string? token))
          (is (= (str "github/" (:id u))
                 (-> token
                     (jwt/unsign pubkey {:alg :rs256})
                     :sub))))))))
