(ns monkey.ci.gui.test.customer.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.subs :as sut]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(deftest alerts
  (let [s (rf/subscribe [:customer/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (db/set-alerts {} a))))
        (is (= a @s)))))

  (testing "with id, returns alerts for given id"
    (let [id ::test-id
          s (rf/subscribe [:customer/alerts id])
          a [{:type :info
              :message "Another test alert"}]]
      (is (nil? @s))
      (is (some? (reset! app-db (db/set-alerts {} id a))))
      (is (= a @s)))))

(deftest customer-info
  (let [ci (rf/subscribe [:customer/info])]
    (testing "exists"
      (is (some? ci)))

    (testing "holds customer info from db"
      (is (nil? @ci))
      (is (map? (reset! app-db (db/set-customer {} ::test-customer))))
      (is (= ::test-customer @ci)))))

(deftest customer-loading?
  (let [l (rf/subscribe [:customer/loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "holds loading state from db"
      (is (not @l))
      (is (map? (reset! app-db (db/set-loading {}))))
      (is (true? @l)))))

(deftest repo-alerts
  (let [s (rf/subscribe [:customer/repo-alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (db/set-repo-alerts {} a))))
        (is (= a @s))))))

(deftest github-repos
  (let [r (rf/subscribe [:customer/github-repos])]
    (testing "exists"
      (is (some? r)))

    (testing "returns repos from db"
      (let [l [{:id "test-repo"}]]
        (is (map? (reset! app-db (db/set-github-repos {} l))))
        (is (= ["test-repo"] (map :id @r)))))

    (testing "marks watched repo"
      (is (map? (reset! app-db (db/set-github-repos {} [{:id "github-repo-id"
                                                         :ssh-url "ssh@ssh-url"
                                                         :clone-url "https://clone-url"}]))))

      (testing "by github id"
        (is (map? (swap! app-db db/set-customer {:repos [{:github-id "github-repo-id"}]})))
        (is (true? (-> @r first :monkeyci/watched?))))
      
      (testing "by clone url"
        (is (map? (swap! app-db db/set-customer {:repos [{:url "https://clone-url"}]})))
        (is (true? (-> @r first :monkeyci/watched?))))
      
      (testing "by ssh url"
        (is (map? (swap! app-db db/set-customer {:repos [{:url "ssh@ssh-url"}]})))
        (is (true? (-> @r first :monkeyci/watched?)))))

    (testing "contains repo info"
      (is (map? (reset! app-db (-> {}
                                   (db/set-github-repos [{:id "github-repo-id"
                                                          :ssh-url "ssh@ssh-url"
                                                          :clone-url "https://clone-url"}])
                                   (db/set-customer {:repos [{:url "ssh@ssh-url"
                                                              :id "test-repo"}]})))))
      (is (= "test-repo" (-> @r first :monkeyci/repo :id))))))

(deftest create-alerts
  (let [s (rf/subscribe [:customer/create-alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (db/set-create-alerts {} a))))
        (is (= a @s))))))

(deftest customer-creating?
  (let [l (rf/subscribe [:customer/creating?])]
    (testing "exists"
      (is (some? l)))

    (testing "holds creating state from db"
      (is (not @l))
      (is (map? (reset! app-db (db/mark-customer-creating {}))))
      (is (true? @l)))))

(deftest customer-recent-builds
  (let [l (rf/subscribe [:customer/recent-builds])]
    (testing "exists"
      (is (some? l)))

    (testing "holds recent builds from db"
      (let [builds [{:id "test build"}]]
        (is (empty? @l))
        (is (map? (reset! app-db (db/set-recent-builds {} builds))))
        (is (= builds @l))))

    (testing "returns recent first"
      (let [[old new :as builds] [{:id "first"
                                   :start-time 100}
                                  {:id "second"
                                   :start-time 200}]]
        (is (map? (reset! app-db (db/set-recent-builds {} builds))))
        (is (= new (first @l)))))))
