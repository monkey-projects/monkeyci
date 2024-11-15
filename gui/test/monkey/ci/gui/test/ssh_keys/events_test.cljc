(ns monkey.ci.gui.test.ssh-keys.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rf-test]
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
