(ns monkey.ci.web.github-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.math :as cm]
            [buddy.sign.jwt :as jwt]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.web
             [auth :as auth]
             [common :as wc]
             [github :as sut]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test
             [aleph-test :as af]
             [runtime :as trt]]
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
          _ (st/save-webhook st {:id "test-hook"})
          req (-> {:runner (fn [_ build]
                             (swap! inv conj build))
                   :storage st}
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}}}))]
      (is (= 202 (:status (sut/webhook req))))
      (is (not= :timeout (h/wait-until #(some? @inv) 1000)))))

  (testing "ignores non-push events"
    (let [inv (atom nil)
          req (-> {:runner (fn [_ build]
                             (swap! inv conj build))}
                  (h/->req)
                  (assoc :headers {"x-github-event" "ping"}
                         :parameters {:body 
                                      {:key "value"}}))]
      (is (= 204 (:status (sut/webhook req))))
      (is (nil? @inv))))

  (testing "ignores ref delete events"
    (let [inv (atom nil)
          st (st/make-memory-storage)
          _ (st/save-webhook st {:id "test-hook"})
          req (-> {:runner (fn [_ build]
                             (swap! inv conj build))
                   :storage st}
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}
                                       :deleted true}}))]
      (is (= 204 (:status (sut/webhook req))))
      (is (empty? @inv))))

  (testing "fires build end event on failure"
    (let [st (st/make-memory-storage)
          _ (st/save-webhook st {:id "test-hook"})
          {:keys [recv] :as e} (h/fake-events)
          req (-> {:runner (fn [rt _]
                             (throw (ex-info "Test error" {:runtime rt})))
                   :storage st
                   :events e}
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}}}))
          build-end? (comp (partial = :build/end) :type)
          recv-build-end (fn []
                           (some build-end? @recv))]
      (is (= 202 (:status (sut/webhook req))))
      (is (not= :timeout (h/wait-until recv-build-end 1000))))))

(deftest app-webhook
  (testing "triggers build on push for watched repo"
    (h/with-memory-store s
      (let [gid "test-id"
            sid (st/watch-github-repo s {:customer-id "test-cust"
                                         :id "test-repo"
                                         :github-id gid})
            runs (atom [])
            req (-> {:storage s
                     :runner (fn [_ build]
                               (swap! runs conj build))}
                    (h/->req)
                    (assoc :headers {"x-github-event" "push"}
                           :parameters {:body
                                        {:repository {:id gid}}}))
            resp (sut/app-webhook req)]
        (is (some? (st/find-repo s (st/ext-repo-sid sid))))
        (is (= 202 (:status resp)))
        (is (= 1 (-> resp :body :builds count)))
        (is (not= :timeout (h/wait-until #(pos? (count @runs)) 500))))))

  (testing "ignores non-push events"
    (h/with-memory-store s
      (let [gid "test-id"
            sid (st/watch-github-repo s {:customer-id "test-cust"
                                         :id "test-repo"
                                         :github-id gid})
            runs (atom [])
            req (-> {:storage s
                     :runner (fn [_ build]
                               (swap! runs conj build))}
                    (h/->req)
                    (assoc :headers {"x-github-event" "other"}
                           :parameters {:body
                                        {:repository {:id gid}}}))
            resp (sut/app-webhook req)]
        (is (some? (st/find-repo s (st/ext-repo-sid sid))))
        (is (= 204 (:status resp)))
        (is (= 0 (-> resp :body :builds count))))))

  (testing "ignores push events for non-watched repos"
    (h/with-memory-store s
      (let [gid "test-id"
            runs (atom [])
            req (-> {:storage s
                     :runner (fn [_ build]
                               (swap! runs conj build))}
                    (h/->req)
                    (assoc :headers {"x-github-event" "push"}
                           :parameters {:body
                                        {:repository {:id gid}}}))
            resp (sut/app-webhook req)]
        (is (= 204 (:status resp)))
        (is (= 0 (-> resp :body :builds count)))))))

(defn- test-webhook []
  (zipmap [:id :customer-id :repo-id] (repeatedly st/new-id)))

(deftest create-build
  (testing "file changes"
    (testing "adds file changes to build"
      (h/with-memory-store s
        (let [b (sut/create-build {:storage s}
                                  {}
                                  {:commits
                                   [{:added ["new-file-1"]
                                     :removed ["removed-file-1"]}
                                    {:added ["new-file-2"]
                                     :modified ["modified-file-1"]}]})]
          (is (= {:added    #{"new-file-1"
                              "new-file-2"}
                  :removed  #{"removed-file-1"}
                  :modified #{"modified-file-1"}}
                 (:changes b))))))))

(deftest create-webhook-build
  (testing "creates build record for customer/repo"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {})]
          (is (some? r))
          (is (p/obj-exists? s (-> wh
                                   (select-keys [:customer-id :repo-id])
                                   (assoc :build-id (:build-id r))
                                   (st/build-sid))))))))

  (testing "build contains commit message"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:message "test message"}})
              id (:sid r)
              md (st/find-build s id)]
          (is (st/sid? id))
          (is (some? md))
          (is (= "test message" (get-in md [:git :message])))))))

  (testing "adds start time as current epoch millis"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:timestamp "2023-10-10"}})
              id (:sid r)
              md (st/find-build s id)]
          (is (st/sid? id))
          (is (some? md))
          (is (number? (:start-time md)))))))
  
  (testing "`nil` if no configured webhook found"
    (h/with-memory-store s
      (is (nil? (sut/create-webhook-build {:storage s}
                                          "test-webhook"
                                          {})))))

  (testing "uses clone url for public repos"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:repository {:ssh-url "ssh-url"
                                                        :clone-url "clone-url"}})]
          (is (= "clone-url" (get-in r [:git :url])))))))

  (testing "uses ssh url if repo is private"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:repository {:ssh-url "ssh-url"
                                                        :clone-url "clone-url"
                                                        :private true}})]
          (is (= "ssh-url" (get-in r [:git :url])))))))

  (testing "does not include secret key in metadata"
    (h/with-memory-store s
      (let [wh (assoc (test-webhook) :secret-key "very very secret!")]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:message "test message"
                                                         :timestamp "2023-10-10"}})
              id (:sid r)
              md (st/find-build s id)]
          (is (not (contains? md :secret-key)))))))

  (testing "adds payload ref to build"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"})]
          (is (= "test-ref" (get-in r [:git :ref])))))))

  (testing "sets `main-branch`"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (= "test-main"
                 (get-in r [:git :main-branch])))))))

  (testing "sets `commit-id`"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:id "test-commit-id"}})]
          (is (= "test-commit-id"
                 (get-in r [:git :commit-id])))))))

  (testing "adds configured ssh key matching repo labels"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:customer-id wh)
            rid (:repo-id wh)
            ssh-key {:id "test-key"
                     :private-key "test-ssh-key"}]
        (is (st/sid? (st/save-webhook s wh)))
        (is (st/sid? (st/save-repo s {:customer cid
                                      :id rid
                                      :labels [{:name "ssh-lbl"
                                                :value "lbl-val"}]})))
        (is (st/sid? (st/save-ssh-keys s cid [ssh-key])))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (= [ssh-key]
                 (get-in r [:git :ssh-keys])))))))

  (testing "sets cleanup flag"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:customer-id wh)
            rid (:repo-id wh)]
        (is (st/sid? (st/save-webhook s wh)))
        (is (st/sid? (st/save-repo s {:customer cid
                                      :id rid})))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (true? (:cleanup? r)))))))

  (testing "assigns idx"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:customer-id wh)
            rid (:repo-id wh)]
        (is (st/sid? (st/save-webhook s wh)))
        (is (st/sid? (st/save-repo s {:customer cid
                                      :id rid})))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (number? (:idx r)))
          (is (= (str "build-" (:idx r)) (:build-id r))))))))

(defn- with-github-user
  "Sets up fake http communication with github to return the given user"
  ([u f]
   (af/with-fake-http [{:url "https://github.com/login/oauth/access_token"
                        :request-method :post}
                       {:status 200
                        :body (h/to-json {:access-token "test-token"})
                        :headers {"Content-Type" "application/json"}}
                       {:url "https://api.github.com/user"
                        :request-method :get}
                       {:status 200
                        :body (h/to-json u)
                        :headers {"Content-Type" "application/json"}}]
     (f u)))
  ([f]
   (with-github-user {:name "test user"
                      :id (int (* (cm/random) 10000))}
     f)))

(deftest login
  (testing "when exchange fails at github, returns body and 400 status code"
    (af/with-fake-http ["https://github.com/login/oauth/access_token"
                        {:status 401
                         :body (h/to-json {:message "invalid access code"})
                         :headers {"Content-Type" "application/json"}}]
      (is (= 400 (-> {:parameters
                      {:query
                       {:code "test-code"}}}
                     (sut/login)
                     :status)))))

  (testing "generates new token and returns it"
    (with-github-user
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
          (is (map? (jwt/unsign token (.getPublic kp) {:alg :rs256})))))))

  (testing "finds existing github user in storage"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
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
          (let [c (st/find-user-by-type st [:github (:id u)])]
            (is (some? c))
            (is (cuid/cuid? (:id c)) "user should have a cuid"))))))

  (testing "sets user id in token"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
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
                     :sub)))))))

  (testing "adds github token to response"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              req (-> rt
                      (h/->req)
                      (assoc :parameters
                             {:query
                              {:code "test-code"}}))
              _ (st/save-user st {:type "github"
                                  :type-id (:id u)
                                  :id (st/new-id)})
              resp (-> req
                       (sut/login)
                       :body)]
          (is (string? (:github-token resp))))))))

(deftest watch-repo
  (let [cust-id (st/new-id)
        {st :storage :as rt} (trt/test-runtime)
        _ (st/save-customer st {:id cust-id :name "test customer"})
        repo {:name "test repo"
              :customer-id cust-id
              :github-id 1234245}
        r (-> rt
              (h/->req)
              (h/with-body repo)
              (sut/watch-repo))]

    (is (= 200 (:status r)))
    
    (testing "adds to watch list"
      (is (= [(:body r)]
             (st/find-watched-github-repos st (:github-id repo)))))

    (testing "creates new repo"
      (is (= (:body r)
             (st/find-repo st [cust-id (get-in r [:body :id])]))))

    (testing "generates display id based on github repo name"
      (is (= "test-repo" (get-in r [:body :id]))))))

(deftest unwatch-repo
  (testing "404 when repo not found"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (assoc :parameters {:path {:customer-id "test-cust"
                                              :repo-id "test-repo"}})
                   (sut/unwatch-repo)
                   :status))))

  (testing "unwatches in db"
    (let [{st :storage :as rt} (trt/test-runtime)
          [cust-id repo-id github-id :as sid] (repeatedly 3 st/new-id)
          _ (st/watch-github-repo st (zipmap [:customer-id :id :github-id] sid))
          req (-> rt
                  (h/->req)
                  (assoc :parameters {:path {:customer-id cust-id
                                             :repo-id repo-id}}))
          resp (sut/unwatch-repo req)]
      (is (= 200 (:status resp)))
      (is (= {:customer-id cust-id
              :id repo-id}
             (:body resp)))
      (is (empty? (st/find-watched-github-repos st github-id))))))
