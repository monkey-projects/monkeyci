(ns monkey.ci.gui.test.artifact.events-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.artifact.db :as db]
            [monkey.ci.gui.artifact.events :as sut]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as tf]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(deftest artifact-download
  (testing "invokes download on backend"
    (let [e (h/catch-fx :http-xhrio)]
      (is (some? (reset! app-db (-> {}
                                    (r/set-current
                                     {:parameters
                                      {:path
                                       {:customer-id "test-cust"
                                        :repo-id "test-repo"
                                        :build-id "test-build"}}})
                                    (ldb/set-token "test-token")))))
      (rf/dispatch-sync [:artifact/download "test-art"])
      (is (= 1 (count @e)))
      (let [inv (first @e)]
        (is (= :get (:method inv)))
        (is (= "http://test:3000/customer/test-cust/repo/test-repo/builds/test-build/artifact/test-art/download"
               (:uri inv)))
        (is (= "Bearer test-token" (get-in inv [:headers "Authorization"]))))))

  (testing "marks artifact as downloading"
    (rf/dispatch-sync [:artifact/download "test-art"])
    (is (db/downloading? @app-db "test-art")))

  (testing "clears alerts"
    (is (some? (reset! app-db (db/set-alerts {} [{:type :info}]))))
    (rf/dispatch-sync [:artifact/download "test-art"])
    (is (nil? (db/alerts @app-db)))))

(deftest artifact-download--success
  (let [art-id (str (random-uuid))]
    (testing "unmarks downloading"
      (let [e (h/catch-fx :download-link)]
        (is (some? (reset! app-db (db/set-downloading {} art-id))))
        (rf/dispatch-sync [:artifact/download--success art-id "test contents"])
        (is (not (db/downloading? @app-db art-id)))))

    (testing "makes download link"
      (let [e (h/catch-fx :download-link)]
        (rf/dispatch-sync [:artifact/download--success art-id "test contents"])
        (is (= [(str art-id ".tgz") "test contents"] (first @e)))))))

(deftest artifact-download--failed
  (let [art-id (str (random-uuid))]
    (testing "unmarks downloading"
      (is (some? (reset! app-db (db/set-downloading {} art-id))))
      (rf/dispatch-sync [:artifact/download--failed art-id "test error"])
      (is (not (db/downloading? @app-db art-id))))

    (testing "sets error alert"
      (rf/dispatch-sync [:artifact/download--failed art-id "test error"])
      (is (= :danger (-> (db/alerts @app-db)
                         first
                         :type))))))
