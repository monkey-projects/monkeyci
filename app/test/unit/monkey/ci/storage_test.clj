(ns monkey.ci.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [config :as c]
             [cuid :as cuid]
             [sid :as sid]
             [storage :as sut]]
            [monkey.ci.helpers :as h]))

(deftest webhook-details
  (testing "webhook-sid is a sid"
    (is (sid/sid? (sut/webhook-sid "test-id"))))
  
  (testing "can create and find"
    (h/with-memory-store st
      (let [id (cuid/random-cuid)
            d {:id id}]
        (is (sid/sid? (sut/save-webhook st d)))
        (is (= d (sut/find-webhook st id)))))))

(deftest build-metadata
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-sid
  (testing "starts with builds"
    (is (= "builds" (first (sut/build-sid {:customer-id "cust"
                                           :repo-id "repo"
                                           :build-id "test-build"}))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (cuid/random-cuid)
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sid/sid? (sut/save-build-results st md {:status :success})))
        (is (= :success (:status (sut/find-build-results st md))))))))

(defn- test-build-sid []
  (repeatedly 3 (comp str random-uuid)))

(deftest patch-build-results
  (testing "reads result, applies f with args, then writes result back"
    (h/with-memory-store st
      (let [sid (test-build-sid)]
        (is (sid/sid? (sut/patch-build-results st sid assoc :key "value")))
        (is (= {:key "value"}
               (sut/find-build-results st sid)))))))

(deftest save-build
  (testing "creates new build"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (-> (zipmap [:customer-id :repo-id :build-id] sid)
                      (assoc :start-time 100))]
        (is (sid/sid? (sut/save-build st build)))
        (is (true? (sut/build-exists? st sid)))
        (is (= build (sut/find-build st sid)))))))

(deftest find-build
  (testing "`nil` if sid is `nil`"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (zipmap [:customer-id :repo-id :build-id] sid)]
        (is (sid/sid? (sut/save-build st build)))
        (is (nil? (sut/find-build st nil))))))

  (testing "retrieves regular build"
    (h/with-memory-store st
      (let [build {:build-id "test-build"
                   :customer-id "test-cust"
                   :repo-id "test-repo"}]
        (is (sid/sid? (sut/save-build st build)))
        (is (= build (sut/find-build st (sut/ext-build-sid build)))))))

  (testing "retrieves legacy build"
    (h/with-memory-store st
      (let [md {:customer-id "test-cust"
                :repo-id "test-repo"
                :build-id "test-build"}
            results {:jobs {"test-job" {:status :success}}}
            sid (sut/ext-build-sid md)]
        (is (sid/sid? (sut/create-build-metadata st md)))
        (is (sid/sid? (sut/save-build-results st sid results)))
        (let [r (sut/find-build st (sut/ext-build-sid md))]
          (is (some? (:jobs r)))
          (is (= "test-cust" (:customer-id r)))
          (is (true? (:legacy? r))))))))

(deftest parameters
  (testing "can store on customer level"
    (h/with-memory-store st
      (let [cid (sut/new-id)
            params {:parameters
                    [{:name "param-1"
                      :value "value 1"}
                     {:name "param-2"
                      :value "value 2"}]
                    :label-filters
                    [[{:label "test-label" :value "test-value"}]]}]
        (is (sid/sid? (sut/save-params st cid params)))
        (is (= params (sut/find-params st cid)))))))

(deftest list-build-ids
  (testing "lists all build ids for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:customer-id :repo-id :build-id] sid))))))
        (let [l (sut/list-build-ids st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds l)))))))

(deftest list-builds
  (testing "lists and fetches all builds for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sid/sid? (sut/save-build
                           st
                           (zipmap [:customer-id :repo-id :build-id] sid))))))
        (let [l (sut/list-builds st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds (map :build-id l))))))))

(deftest find-latest-build
  (testing "retrieves latest build by build id"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            build-ids (->> (range)
                           (map (partial format "build-%d"))
                           (take 2))
            builds (map (comp (partial zipmap [:customer-id :repo-id :build-id])
                              (partial conj repo-sid))
                        build-ids)]
        (doseq [b builds]
          (is (sid/sid? (sut/save-build st b))))
        (let [l (sut/find-latest-build st repo-sid)]
          (is (= (last builds) l)))))))

(deftest list-builds-since
  (testing "retrieves builds since given timestamp"
    (h/with-memory-store st
      (let [cust-id (sut/new-id)
            repo-id (sut/new-id)
            old-build {:customer-id cust-id
                       :repo-id repo-id
                       :start-time 100}
            new-build {:customer-id cust-id
                       :repo-id repo-id
                       :start-time 200}]
        (is (sid/sid? (sut/save-customer st {:id cust-id
                                             :repos {repo-id {:id repo-id}}})))
        (is (sid/sid? (sut/save-build st old-build)))
        (is (sid/sid? (sut/save-build st new-build)))
        (is (= [new-build] (sut/list-builds-since st cust-id 150)))))))

(deftest find-next-build-idx
  (testing "max build idx plus one for this repo"
    (h/with-memory-store st
      (let [repo-sid (sid/->sid (repeatedly 2 cuid/random-cuid))
            build-ids (->> (range)
                           (map (partial format "build-%d"))
                           (take 2))
            builds (->> (range)
                        (map (fn [idx]
                               (-> (zipmap [:customer-id :repo-id :build-id]
                                           (conj repo-sid (format "build-%d" idx)))
                                   (assoc :idx (inc idx)))))
                        (take 10))]
        (doseq [b builds]
          (is (sid/sid? (sut/save-build st b))))
        (is (= (->> builds
                    (map :idx)
                    sort
                    last
                    inc)
               (sut/find-next-build-idx st repo-sid)))))))

(deftest save-ssh-keys
  (testing "writes ssh keys object"
    (h/with-memory-store st
      (let [cid (sut/new-id)
            ssh-keys [{:id (sut/new-id)
                       :description "test ssh key"
                       :private-key "test-key"}]]
        (is (sid/sid? (sut/save-ssh-keys st cid ssh-keys)))
        (is (= ssh-keys (sut/find-ssh-keys st cid)))))))

(deftest users
  (let [u {:type "github"
               :type-id 1234
               :id (sut/new-id)
               :name "test user"
           :email "test@monkeyci.com"}]
    
    (testing "can save and find github user"
      (h/with-memory-store st
        (is (sid/sid? (sut/save-user st u)))
        (is (= u (sut/find-user-by-type st [:github 1234])) "can retrieve user by github id")))

    (testing "can save and find user by cuid"
      (h/with-memory-store st
        (is (sid/sid? (sut/save-user st u)))
        (is (= u (sut/find-user st (:id u))) "can retrieve user by id")))))

(deftest update-repo
  (testing "updates repo in customer object"
    (h/with-memory-store st
      (let [[cid rid] (repeatedly sut/new-id)]
        (is (some? (sut/save-customer st {:id cid})))
        (is (some? (sut/save-repo st {:id rid
                                      :customer-id cid
                                      :url "http://test-repo"})))
        (is (some? (sut/update-repo st [cid rid] assoc :url "updated-url")))
        (is (= "updated-url" (:url (sut/find-repo st [cid rid]))))))))

(deftest watch-github-repo
  (h/with-memory-store st
    (let [[cid rid gid] (repeatedly sut/new-id)
          repo {:id rid
                :customer-id cid
                :github-id gid
                :url "http://test-repo"}]

      (testing "new repo"
        (let [sid (sut/watch-github-repo st repo)]
          
          (testing "creates repo"
            (is (sid/sid? sid))
            (is (= repo (sut/find-repo st [cid rid]))))

          (testing "adds to watched repos"
            (is (= [repo] (sut/find-watched-github-repos st gid))))))

      (testing "sets github id in existing repo"
        (is (sid/sid? (sut/save-repo st (dissoc repo :github-id))))
        (is (sid/sid? (sut/watch-github-repo st repo)))
        (is (= gid (:github-id (sut/find-repo st [cid rid]))))))))

(deftest unwatch-github-repo
  (h/with-memory-store st
    (let [[cid rid gid] (repeatedly sut/new-id)
          repo {:id rid
                :customer-id cid
                :github-id gid
                :url "http://test-repo"}
          _ (sut/watch-github-repo st repo)
          r (sut/unwatch-github-repo st [cid rid])]

      (is (true? r))
      
      (testing "removes from watch list"
        (is (empty? (sut/find-watched-github-repos st gid))))

      (testing "removes github id from repo"
        (is (= (dissoc repo :github-id)
               (-> (sut/find-repo st [cid rid]))))))))

(deftest join-requests
  (h/with-memory-store st
    (let [req (->> (repeatedly 3 sut/new-id)
                   (zipmap [:id :user-id :customer-id]))]
      (testing "can save and find"
        (is (sid/sid? (sut/save-join-request st req)))
        (is (= req (sut/find-join-request st (:id req)))))

      (testing "can list for user"
        (is (= [req] (sut/list-user-join-requests st (:user-id req))))))))

(deftest normalize-key
  (testing "normalizes string type"
    (is (= :memory (-> (c/normalize-key :storage {:storage {:type "memory"}})
                       :storage
                       :type))))

  (testing "normalizes oci credentials"
    (is (map? (-> (c/normalize-key :storage {:storage
                                             {:type :oci}
                                             :oci
                                             {:credentials {:fingerprint "test-fingerprint"}}})
                  :storage
                  :credentials)))))
