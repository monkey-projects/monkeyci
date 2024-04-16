(ns monkey.ci.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci
             [config :as c]
             [storage :as sut]]
            [monkey.ci.helpers :as h]))

(deftest webhook-details
  (testing "sid is a vector"
    (is (vector? (sut/webhook-sid "test-id"))))
  
  (testing "can create and find"
    (h/with-memory-store st
      (let [id (str (random-uuid))
            d {:id id}]
        (is (sut/sid? (sut/save-webhook-details st d)))
        (is (= d (sut/find-details-for-webhook st id)))))))

(deftest build-metadata
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (str (random-uuid))
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-sid
  (testing "starts with builds"
    (is (= "builds" (first (sut/build-sid {:customer-id "cust"
                                           :repo-id "repo"
                                           :build-id "test-build"}))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (str (random-uuid))
            md {:build-id build-id
                :repo-id "test-repo"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/save-build-results st md {:status :success})))
        (is (= :success (:status (sut/find-build-results st md))))))))

(defn- test-build-sid []
  (repeatedly 3 (comp str random-uuid)))

(deftest patch-build-results
  (testing "reads result, applies f with args, then writes result back"
    (h/with-memory-store st
      (let [sid (test-build-sid)]
        (is (sut/sid? (sut/patch-build-results st sid assoc :key "value")))
        (is (= {:key "value"}
               (sut/find-build-results st sid)))))))

(deftest save-build
  (testing "creates new build"
    (h/with-memory-store st
      (let [[cust-id repo-id build-id :as sid] (test-build-sid)
            build (-> (zipmap [:customer-id :repo-id :build-id] sid)
                      (assoc :start-time 100))]
        (is (sut/sid? (sut/save-build st build)))
        (is (true? (sut/build-exists? st sid)))
        (is (= build (sut/find-build st sid)))))))

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
        (is (sut/sid? (sut/save-params st cid params)))
        (is (= params (sut/find-params st cid)))))))

(deftest list-builds
  (testing "lists all builds for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sut/sid? (sut/save-build
                           st
                           (zipmap [:customer-id :repo-id :build-id] sid))))))
        (let [l (sut/list-builds st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds l)))))))

(deftest save-ssh-keys
  (testing "writes ssh keys object"
    (h/with-memory-store st
      (let [cid (sut/new-id)
            ssh-keys [{:id (sut/new-id)
                       :description "test ssh key"
                       :private-key "test-key"}]]
        (is (sut/sid? (sut/save-ssh-keys st cid ssh-keys)))
        (is (= ssh-keys (sut/find-ssh-keys st cid)))))))

(deftest users
  (testing "can save and find github user"
    (h/with-memory-store st
      (let [u {:type "github"
               :type-id 1234
               :id (sut/new-id)
               :name "test user"
               :email "test@monkeyci.com"}]
        (is (sut/sid? (sut/save-user st u)))
        (is (= u (sut/find-user st [:github 1234])) "can retrieve user by github id")))))

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
            (is (sut/sid? sid))
            (is (= repo (sut/find-repo st [cid rid]))))

          (testing "adds to watched repos"
            (is (= [repo] (sut/find-watched-github-repos st gid))))))

      (testing "sets github id in existing repo"
        (is (sut/sid? (sut/save-repo st (dissoc repo :github-id))))
        (is (sut/sid? (sut/watch-github-repo st repo)))
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
