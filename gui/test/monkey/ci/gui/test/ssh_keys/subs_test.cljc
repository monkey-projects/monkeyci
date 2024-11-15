(ns monkey.ci.gui.test.ssh-keys.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.ssh-keys.db :as db]
            [monkey.ci.gui.ssh-keys.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest loading?
  (h/verify-sub [:ssh-keys/loading?]
                db/set-loading
                true
                false))

(deftest alerts
  (let [alerts [{:type :info}]]
    (h/verify-sub [:ssh-keys/alerts]
                  #(db/set-alerts % alerts)
                  alerts
                  nil)))

(deftest ssh-keys
  (let [ssh-keys [{:description "test key"}]]
    (h/verify-sub [:ssh-keys/keys]
                  #(db/set-value % ssh-keys)
                  ssh-keys
                  nil)))

(deftest editing-keys
  (let [ssh-keys [{:description "test key"}]]
    (h/verify-sub [:ssh-keys/editing-keys]
                  #(db/set-editing-keys % ssh-keys)
                  ssh-keys
                  nil)))

(deftest editing
  (let [existing {:id "existing-key"}
        new {:temp-id (random-uuid)}
        not-editing {:id "not-editing-key"}]

    (is (some? (reset! app-db (db/set-editing-keys {} [existing new]))))

    (testing "contains editing version of existing key"
      (is (= existing @(rf/subscribe [:ssh-keys/editing existing]))))

    (testing "contains editing version of new key"
      (is (= new @(rf/subscribe [:ssh-keys/editing new]))))

    (testing "`nil` when key is not being edited"
      (is (nil? @(rf/subscribe [:ssh-keys/editing not-editing]))))))

(deftest display-keys
  (let [d (rf/subscribe [:ssh-keys/display-keys])]
    (testing "exists"
      (is (some? d)))

    (testing "returns all keys with editing keys replaced"
      (is (empty? @d))
      (is (some? (reset! app-db (-> {}
                                    (db/set-value
                                     [{:id "existing-key"}
                                      {:id "other-key"}])
                                    (db/set-editing-keys
                                     [{:id "other-key"
                                       :description "updated description"}])))))
      (is (= [{:id "existing-key"}
              {:id "other-key"
               :description "updated description"
               :editing? true}]
             @d)))

    (testing "includes new keys"
      (let [new-key {:temp-id (random-uuid)}]
        (is (some? (swap! app-db db/set-editing-keys [new-key])))
        (is (= [{:id "existing-key"}
                {:id "other-key"}
                (assoc new-key :editing? true)]
               @d))))))
