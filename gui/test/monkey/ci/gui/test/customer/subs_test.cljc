(ns monkey.ci.gui.test.customer.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.customer.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest alerts
  (let [s (rf/subscribe [:customer/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns customer alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (lo/set-alerts {} db/customer a))))
        (is (= a @s))))))

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
      (is (map? (reset! app-db (lo/set-loading {} db/customer))))
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
      
      (testing "not by clone url"
        (is (map? (swap! app-db db/set-customer {:repos [{:url "https://clone-url"}]})))
        (is (not (-> @r first :monkeyci/watched?))))
      
      (testing "not by ssh url"
        (is (map? (swap! app-db db/set-customer {:repos [{:url "ssh@ssh-url"}]})))
        (is (not (-> @r first :monkeyci/watched?)))))

    (testing "contains repo info"
      (is (map? (reset! app-db (-> {}
                                   (db/set-github-repos [{:id "github-repo-id"
                                                          :ssh-url "ssh@ssh-url"
                                                          :clone-url "https://clone-url"}])
                                   (db/set-customer {:repos [{:url "ssh@ssh-url"
                                                              :id "test-repo"
                                                              :github-id "github-repo-id"}]})))))
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
        (is (map? (reset! app-db (lo/set-value {} db/recent-builds builds))))
        (is (= builds (->> @l (map #(select-keys % [:id])))))))

    (testing "returns recent first"
      (let [[old new :as builds] [{:id "first"
                                   :start-time 100}
                                  {:id "second"
                                   :start-time 200}]]
        (is (map? (reset! app-db (lo/set-value {} db/recent-builds builds))))
        (is (= (:id new) (:id (first @l))))))

    (testing "adds repo name from customer info"
      (is (some? (reset! app-db (-> {}
                                    (lo/set-value db/recent-builds [{:id "test-build"
                                                                     :repo-id "test-repo"}])
                                    (db/set-customer {:repos [{:id "test-repo"
                                                               :name "test repo name"}]})))))
      (is (= "test repo name" (-> @l first :repo :name))))))

(deftest customer-stats
  (h/verify-sub [:customer/stats] #(lo/set-value % db/stats ::test-stats) ::test-stats nil))

(deftest customer-credits
  (h/verify-sub [:customer/credits] #(lo/set-value % db/credits ::test-creds) ::test-creds nil))

(deftest credit-stats
  (h/verify-sub [:customer/credit-stats]
                (fn [db]
                  (-> db
                      (lo/set-value db/credits {:available 100})
                      (lo/set-value db/stats {:stats
                                              {:consumed-credits
                                               [{:credits 10}
                                                {:credits 5}]}})))
                {:available 100
                 :consumed 15}
                nil))

(deftest customer-labels
  (let [l (rf/subscribe [:customer/labels])]
    (testing "exists"
      (is (some? l)))

    (testing "provides distinct labels for all customer repos, sorted"
      (is (empty? @l))
      (is (some? (reset! app-db (db/set-customer
                                 {}
                                 {:id "test-cust"
                                  :name "Test customer"
                                  :repos [{:id "repo-1"
                                           :name "Test repo 1"
                                           :labels
                                           [{:name "label-1"
                                             :value "value 1"}
                                            {:name "label-2"
                                             :value "value 2"}]}
                                          {:id "repo-2"
                                           :name "Test repo 2"
                                           :labels
                                           [{:name "label-1"
                                             :value "value 3"}
                                            {:name "label-3"
                                             :value "value 4"}]}]}))))
      (is (= ["label-1" "label-2" "label-3"]
             @l)))))

(deftest customer-group-by-lbl
  (h/verify-sub
   [:customer/group-by-lbl]
   #(db/set-group-by-lbl % "test-lbl")
   "test-lbl"
   "project"))

(deftest customer-grouped-repos
  (let [r (rf/subscribe [:customer/grouped-repos])]
    (testing "exists"
      (is (some? r)))

    (testing "returns repos, grouped by label from db"
      (let [[repo-1 repo-2 repo-3 :as repos]
            [{:id "repo-1"
              :labels
              [{:name "label-1"
                :value "value 1"}
               {:name "label-2"
                :value "value 1"}]}
             {:id "repo-2"
              :labels
              [{:name "label-2"
                :value "value 1"}]}
             {:id "repo-3"
              :labels
              [{:name "label-2"
                :value "value 2"}]}]]
        (is (some? (reset! app-db (-> {}
                                      (db/set-group-by-lbl "label-2")
                                      (db/set-customer
                                       {:repos repos})))))
        (is (= {"value 1" [repo-1 repo-2]
                "value 2" [repo-3]}
               @r))))))
