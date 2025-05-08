(ns monkey.ci.storage.cached-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.storage.cached :as sut]))

(defrecord CountingStorage [src counts]
  p/Storage
  (read-obj [_ sid]
    (swap! counts update :reads (fnil inc 0))
    (p/read-obj src sid))

  (write-obj [_ sid obj]
    (swap! counts update :writes (fnil inc 0))
    (p/write-obj src sid obj))

  (obj-exists? [_ sid]
    (swap! counts update :exists (fnil inc 0))
    (p/obj-exists? src sid))

  (list-obj [_ sid]
    (swap! counts update :lists (fnil inc 0))
    (p/list-obj src sid)))

(deftest cached-storage
  (testing "can write and read object"
    (let [org {:id (random-uuid)
                :name "test org"}
          src (st/make-memory-storage)
          st (sut/->CachedStorage src (st/make-memory-storage))]
      (is (st/sid? (st/save-org st org)))
      (is (= org (st/find-org st (:id org))))))

  (testing "does not read from src if cached"
    (let [c (atom {:reads 0})
          cs (sut/->CachedStorage
              (->CountingStorage
               (st/make-memory-storage)
               c)
              (st/make-memory-storage))
          id (random-uuid)]
      (is (st/sid? (st/save-org cs {:id id
                                         :name "test org"})))
      (is (= 0 (:reads @c)))
      (is (some? (st/find-org cs id)))
      (is (= 0 (:reads @c)))
      (is (some? (st/find-org cs id)))
      (is (= 0 (:reads @c)))
      ;; Read another one
      (is (nil? (st/find-org cs (random-uuid))))
      (is (= 1 (:reads @c)))))

  (testing "delete also removes from cache"
    (let [id (random-uuid)
          org {:id id
                :name "test org"}
          src (st/make-memory-storage)
          st (sut/->CachedStorage src (st/make-memory-storage))]
      (is (st/sid? (st/save-org st org)))
      (is (true? (p/delete-obj st (st/org-sid id))))
      (is (nil? (st/find-org st id)))
      (is (nil? (st/find-org (-> st .cache) id)))))

  (testing "checks existing object from src"
    (let [c (atom {:exists 0})
          cs (->CountingStorage
              (st/make-memory-storage)
              c)
          s (sut/->CachedStorage (st/make-memory-storage) cs)]
      (is (false? (p/obj-exists? s ["test-id"])))
      (is (= 0 (:exists @c)))))

  (testing "lists from src"
    (let [c (atom {:lists 0})
          cs (->CountingStorage
              (st/make-memory-storage)
              c)
          s (sut/->CachedStorage (st/make-memory-storage) cs)
          id (random-uuid)]
      (is (st/sid? (st/save-org s {:id id :name "test org"})))
      (is (= [id] (p/list-obj s [st/global "orgs"])) "list first time")
      (is (= [id] (p/list-obj s [st/global "orgs"])) "list again")
      (is (= 0 (:lists @c)) "expected no listings from cache"))))
