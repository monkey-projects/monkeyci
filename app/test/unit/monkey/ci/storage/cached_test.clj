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
    (let [cust {:id (random-uuid)
                :name "test customer"}
          src (st/make-memory-storage)
          st (sut/->CachedStorage src (st/make-memory-storage))]
      (is (st/sid? (st/save-customer st cust)))
      (is (= cust (st/find-customer st (:id cust))))))

  (testing "does not read from src if cached"
    (let [c (atom {:reads 0})
          cs (sut/->CachedStorage
              (->CountingStorage
               (st/make-memory-storage)
               c)
              (st/make-memory-storage))
          id (random-uuid)]
      (is (st/sid? (st/save-customer cs {:id id
                                         :name "test customer"})))
      (is (= 0 (:reads @c)))
      (is (some? (st/find-customer cs id)))
      (is (= 0 (:reads @c)))
      (is (some? (st/find-customer cs id)))
      (is (= 0 (:reads @c)))
      ;; Read another one
      (is (nil? (st/find-customer cs (random-uuid))))
      (is (= 1 (:reads @c)))))

  (testing "delete also removes from cache"
    (let [id (random-uuid)
          cust {:id id
                :name "test customer"}
          src (st/make-memory-storage)
          st (sut/->CachedStorage src (st/make-memory-storage))]
      (is (st/sid? (st/save-customer st cust)))
      (is (true? (p/delete-obj st (st/customer-sid id))))
      (is (nil? (st/find-customer st id)))
      (is (nil? (st/find-customer (-> st .cache) id)))))

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
      (is (st/sid? (st/save-customer s {:id id :name "test customer"})))
      (is (= [id] (p/list-obj s [st/global "customers"])) "list first time")
      (is (= [id] (p/list-obj s [st/global "customers"])) "list again")
      (is (= 0 (:lists @c)) "expected no listings from cache"))))
