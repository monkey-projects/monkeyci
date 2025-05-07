(ns monkey.ci.web.github-test
  (:require [buddy.sign.jwt :as jwt]
            [clojure
             [math :as cm]
             [test :refer [deftest is testing]]]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.test
             [aleph-test :as af]
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web
             [auth :as auth]
             [github :as sut]
             [response :as r]]))

(deftest webhook
  (let [cid (cuid/random-cuid)
        {st :storage :as rt} (trt/test-runtime)
        _ (st/save-webhook st {:id "test-hook"
                               :org-id cid})
        _ (st/save-org-credit st {:org-id cid
                                  :amount 1000})
        req (-> rt
                (h/->req)
                (assoc :headers {"x-github-event" "push"}
                       :parameters {:path {:id "test-hook"}
                                    :body
                                    {:head-commit {:id "test-id"}}}))
        resp (sut/webhook req)]
    (is (= 202 (:status resp)))

    (let [[evt :as evts] (->> resp (r/get-events))]
      (testing "posts `build/triggered` event"
        (is (= [:build/triggered] (map :type evts)))
        (is (some? (:sid evt))))

      (testing "does not store build in db yet"
        (is (nil? (st/find-build st (:sid evt)))))))

  (testing "ignores non-push events"
    (let [req (-> (trt/test-runtime)
                  (h/->req)
                  (assoc :headers {"x-github-event" "ping"}
                         :parameters {:body 
                                      {:key "value"}}))
          resp (sut/webhook req)]
      (is (= 204 (:status resp)))
      (is (empty? (r/get-events resp)))))

  (testing "ignores ref delete events"
    (let [{st :storage :as rt} (trt/test-runtime)
          _ (st/save-webhook st {:id "test-hook"})
          req (-> rt
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:path {:id "test-hook"}
                                      :body
                                      {:head-commit {:id "test-id"}
                                       :deleted true}}))
          resp (sut/webhook req)]
      (is (= 204 (:status resp)))
      (is (empty? (r/get-events resp))))))

(deftest app-webhook
  (testing "triggers build on push for watched repo"
    (let [[gid cid] (repeatedly cuid/random-cuid)
          {s :storage :as rt} (trt/test-runtime)
          sid (st/watch-github-repo s {:org-id cid
                                       :id "test-repo"
                                       :github-id gid})
          _ (st/save-org-credit s {:org-id cid
                                   :amount 1000})
          req (-> rt
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:body
                                      {:repository {:id gid}}}))
          repo-sid (st/ext-repo-sid sid)
          resp (sut/app-webhook req)]
      (is (some? (st/find-repo s repo-sid)))
      (is (= 202 (:status resp)))
      (is (= 1 (-> resp :body :builds count)))
      (let [[evt :as evts] (r/get-events resp)]
        (is (= [:build/triggered] (map :type evts)))
        (is (some? (:build evt)))
        (is (= repo-sid (:sid evt))))))

  (testing "ignores non-push events"
    (let [gid (cuid/random-cuid)
          {s :storage :as rt} (trt/test-runtime)
          sid (st/watch-github-repo s {:org-id "test-org"
                                       :id "test-repo"
                                       :github-id gid})
          req (-> rt
                  (h/->req)
                  (assoc :headers {"x-github-event" "other"}
                         :parameters {:body
                                      {:repository {:id gid}}}))
          resp (sut/app-webhook req)]
      (is (some? (st/find-repo s (st/ext-repo-sid sid))))
      (is (= 204 (:status resp)))
      (is (= 0 (-> resp :body :builds count)))
      (is (empty? (r/get-events resp)))))

  (testing "ignores push events for non-watched repos"
    (let [req (-> (trt/test-runtime)
                  (h/->req)
                  (assoc :headers {"x-github-event" "push"}
                         :parameters {:body
                                      {:repository {:id "unwatched"}}}))
          resp (sut/app-webhook req)]
      (is (= 204 (:status resp)))
      (is (= 0 (-> resp :body :builds count)))
      (is (empty? (r/get-events resp))))))

(defn- test-webhook []
  (zipmap [:id :org-id :repo-id] (repeatedly st/new-id)))

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
  (testing "does not create build record for org/repo"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {})]
          (is (some? r))
          (is (not (p/obj-exists? s (-> wh
                                        (select-keys [:org-id :repo-id])
                                        (assoc :build-id (:build-id r))
                                        (st/build-sid)))))))))

  (testing "build contains commit message"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:message "test message"}})]
          (is (= "test message" (get-in r [:git :message])))))))

  (testing "adds start time as current epoch millis"
    (h/with-memory-store s
      (let [wh (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:head-commit {:timestamp "2023-10-10"}})]
          (is (number? (:start-time r)))))))
  
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

  (testing "adds configured encrypted ssh key matching repo labels"
    (h/with-memory-store s
      (let [wh (test-webhook)
            cid (:org-id wh)
            rid (:repo-id wh)
            ssh-key {:id "test-key"
                     :private-key "encrypted-key"}]
        (is (st/sid? (st/save-webhook s wh)))
        (is (st/sid? (st/save-repo s {:org-id cid
                                      :id rid
                                      :labels [{:name "ssh-lbl"
                                                :value "lbl-val"}]})))
        (is (st/sid? (st/save-ssh-keys s cid [ssh-key])))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (= [{:id (:id ssh-key)
                   :private-key "encrypted-key"}]
                 (get-in r [:git :ssh-keys])))))))

  (testing "assigns id"
    (h/with-memory-store s
      (let [{cid :org-id rid :repo-id :as wh} (test-webhook)]
        (is (st/sid? (st/save-webhook s wh)))
        (is (st/sid? (st/save-repo s {:org cid
                                      :id rid})))
        (let [r (sut/create-webhook-build {:storage s}
                                          (:id wh)
                                          {:ref "test-ref"
                                           :repository {:master-branch "test-main"}})]
          (is (cuid/cuid? (:id r))))))))

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
          (let [u (jwt/unsign token (.getPublic kp) {:alg :rs256})]
            (is (map? u))
            (is (re-matches #"^github/.*" (:sub u))))))))

  (testing "finds existing github user in storage"
    (with-github-user
      (fn [u]
        (let [{st :storage :as rt} (trt/test-runtime)
              _ (st/save-user st {:type "github"
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
  (let [org-id (st/new-id)
        {st :storage :as rt} (trt/test-runtime)
        _ (st/save-org st {:id org-id :name "test org"})
        repo {:name "test repo"
              :org-id org-id
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
             (st/find-repo st [org-id (get-in r [:body :id])]))))

    (testing "generates display id based on github repo name"
      (is (= "test-repo" (get-in r [:body :id]))))))

(deftest unwatch-repo
  (testing "404 when repo not found"
    (is (= 404 (-> (trt/test-runtime)
                   (h/->req)
                   (assoc :parameters {:path {:org-id "test-org"
                                              :repo-id "test-repo"}})
                   (sut/unwatch-repo)
                   :status))))

  (testing "unwatches in db"
    (let [{st :storage :as rt} (trt/test-runtime)
          [org-id repo-id github-id :as sid] (repeatedly 3 st/new-id)
          _ (st/watch-github-repo st (zipmap [:org-id :id :github-id] sid))
          req (-> rt
                  (h/->req)
                  (assoc :parameters {:path {:org-id org-id
                                             :repo-id repo-id}}))
          resp (sut/unwatch-repo req)]
      (is (= 200 (:status resp)))
      (is (= {:org-id org-id
              :id repo-id}
             (:body resp)))
      (is (empty? (st/find-watched-github-repos st github-id))))))

(deftest refresh-token
  (testing "posts to github api with refresh token"
    (af/with-fake-http [{:url "https://github.com/login/oauth/access_token"
                         :request-method :post}
                        (fn [req]
                          {:status 200
                           :headers {"Content-Type" "application/json"}
                           :body (h/to-json {:access-token "new-token"
                                             :refresh-token "new-refresh-token"})})]
      (is (= {:access-token "new-token"
              :refresh-token "new-refresh-token"}
             (-> (trt/test-runtime)
                 (assoc-in [:config :github] {:client-id "test-client-id"
                                              :client-secret "test-client-secret"})
                 (h/->req)
                 (assoc-in [:parameters :body :refresh-token] "old-refresh-token")
                 (sut/refresh-token)
                 :body))))))
