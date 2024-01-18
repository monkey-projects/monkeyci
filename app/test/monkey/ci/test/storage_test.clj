(ns monkey.ci.test.storage-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [monkey.ci.storage :as sut]
            [monkey.ci.test.helpers :as h]))

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
                :project-id "test-project"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/create-build-metadata st md)))
        (is (= md (sut/find-build-metadata st md)))))))

(deftest build-sid
  (testing "starts with builds"
    (is (= "builds" (first (sut/build-sid {:customer-id "cust"
                                           :project-id "proj"
                                           :repo-id "repo"
                                           :build-id "test-build"}))))))

(deftest build-results
  (testing "can create and find"
    (h/with-memory-store st
      (let [build-id (str (random-uuid))
            md {:build-id build-id
                :repo-id "test-repo"
                :project-id "test-project"
                :customer-id "test-cust"}]
        (is (sut/sid? (sut/save-build-results st md {:status :success})))
        (is (= :success (:status (sut/find-build-results st md))))))))

(deftest projects
  (testing "stores project in customer"
    (h/with-memory-store st
      (is (sut/sid? (sut/save-project st {:customer-id "test-customer"
                                          :id "test-project"
                                          :name "Test project"})))
      (is (some? (:projects (sut/find-customer st "test-customer"))))))

  (testing "updates existing project in customer"
    (h/with-memory-store st
      (let [[cid pid] (repeatedly sut/new-id)]
        (is (sut/sid? (sut/save-project st {:customer-id cid
                                            :id pid
                                            :name "Test project"})))
        (is (sut/sid? (sut/save-project st {:customer-id cid
                                            :id pid
                                            :name "Updated project"})))
        (is (= "Updated project" (:name (sut/find-project st [cid pid])))))))

  (testing "can find project via customer"
    (h/with-memory-store st
      (let [cid "some-customer"
            pid "some-project"
            p {:customer-id cid
               :id pid
               :name "Test project"}]
        (is (sut/sid? (sut/save-project st p)))
        (is (= p (sut/find-project st [cid pid])))))))

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

(deftest patch-build-results
  (testing "reads result, applies f with args, then writes result back"
    (h/with-memory-store st
      (let [sid (repeatedly 4 (comp str random-uuid))]
        (is (sut/sid? (sut/patch-build-results st sid assoc :key "value")))
        (is (= {:key "value"}
               (sut/find-build-results st sid)))))))

(deftest list-builds
  (testing "lists all builds for given repo"
    (h/with-memory-store st
      (let [repo-sid ["test-customer" "test-project" "test-repo"]
            builds (->> (range)
                        (map (partial format "build-%d"))
                        (take 2))]
        (doseq [b builds]
          (let [sid (conj repo-sid b)]
            (is (sut/sid? (sut/create-build-metadata
                           st
                           (zipmap [:customer-id :project-id :repo-id :build-id] sid))))))
        (let [l (sut/list-builds st repo-sid)]
          (is (= (count builds) (count l)))
          (is (= builds l)))))))
