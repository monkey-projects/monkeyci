(ns monkey.ci.gui.test.ssh-keys.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.ssh-keys.db :as db]
            [monkey.ci.gui.ssh-keys.events :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(deftest ssh-keys-load
  (testing "sends request to backend"
    (rf-test/run-test-sync
     (let [ssh-keys [{:description "test key"
                      :private-key "test-private"
                      :public-key "test-public"}]
           c (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-customer-ssh-keys {:status 200
                                                      :body ssh-keys
                                                      :error-code :no-error}})
       (is (some? (:martian.re-frame/martian @app-db)))
       (rf/dispatch [:ssh-keys/load "test-customer"])
       (is (= 1 (count @c)))
       (is (= :get-customer-ssh-keys
              (-> @c first (nth 2))))
       (is (= {:customer-id "test-customer"}
              (-> @c first (nth 3))))))))

(deftest ssh-keys-load--success
  (testing "sets ssh keys in db"
    (let [keys [{:description "test ssh key"}]]
      (rf/dispatch-sync [:ssh-keys/load--success {:body keys}])
      (is (= keys (db/get-value @app-db))))))

(deftest ssh-keys-load--failed
  (testing "sets alert"
    (rf/dispatch-sync [:ssh-keys/load--failed "test error"])
    (is (= 1 (count (db/get-alerts @app-db))))))

(deftest ssh-keys-new
  (testing "adds new empty key set to list of editing keys"
    (is (empty? (db/get-editing-keys @app-db)))
    (rf/dispatch-sync [:ssh-keys/new])
    (let [k (db/get-editing-keys @app-db)]
      (is (= 1 (count k)))
      (is (some? (:temp-id (first k)))
          "new key set should have temp id"))))

(deftest cancel-set
  (testing "removes new set from editing list"
    (let [new-set {:temp-id (random-uuid)}]
      (is (some? (reset! app-db (db/set-editing-keys {} [new-set]))))
      (rf/dispatch-sync [:ssh-keys/cancel-set new-set])
      (is (empty? (db/get-editing-keys @app-db)))))

  (testing "removes existing set from editing list"
    (let [ex-set {:id "existing-set"}]
      (is (some? (reset! app-db (db/set-editing-keys {} [ex-set]))))
      (rf/dispatch-sync [:ssh-keys/cancel-set ex-set])
      (is (empty? (db/get-editing-keys @app-db))))))

(deftest prop-changed
  (testing "updates property in ssh key"
    (let [key {:id "test-key"}]
      (is (some? (reset! app-db (db/set-editing-keys {} [key]))))
      (rf/dispatch-sync [:ssh-keys/prop-changed key :description "updated value"])
      (is (= [{:id "test-key"
               :description "updated value"}]
             (db/get-editing-keys @app-db))))))
