(ns monkey.ci.gui.test.org.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.apis.bitbucket :as bb]
            [monkey.ci.gui.apis.github :as github]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.org.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest alerts
  (let [s (rf/subscribe [:org/alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns org alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (lo/set-alerts {} db/org a))))
        (is (= a @s))))))

(deftest org-info
  (let [ci (rf/subscribe [:org/info])]
    (testing "exists"
      (is (some? ci)))

    (testing "holds org info from db"
      (is (nil? @ci))
      (is (map? (reset! app-db (db/set-org {} ::test-org))))
      (is (= ::test-org @ci)))))

(deftest org-loading?
  (let [l (rf/subscribe [:org/loading?])]
    (testing "exists"
      (is (some? l)))

    (testing "holds loading state from db"
      (is (not @l))
      (is (map? (reset! app-db (lo/set-loading {} db/org))))
      (is (true? @l)))))

(deftest github-repos
  (let [r (rf/subscribe [:org/github-repos])]
    (testing "exists"
      (is (some? r)))

    (testing "returns repos from db"
      (let [l [{:id "test-repo"}]]
        (is (map? (reset! app-db (github/set-repos {} l))))
        (is (= ["test-repo"] (map :id @r)))))

    (testing "marks watched repo"
      (is (map? (reset! app-db (github/set-repos {} [{:id "github-repo-id"
                                                      :ssh-url "ssh@ssh-url"
                                                      :clone-url "https://clone-url"}]))))

      (testing "by github id"
        (is (map? (swap! app-db db/set-org {:repos [{:github-id "github-repo-id"}]})))
        (is (true? (-> @r first :monkeyci/watched?))))
      
      (testing "not by clone url"
        (is (map? (swap! app-db db/set-org {:repos [{:url "https://clone-url"}]})))
        (is (not (-> @r first :monkeyci/watched?))))
      
      (testing "not by ssh url"
        (is (map? (swap! app-db db/set-org {:repos [{:url "ssh@ssh-url"}]})))
        (is (not (-> @r first :monkeyci/watched?)))))

    (testing "contains repo info"
      (is (map? (reset! app-db (-> {}
                                   (github/set-repos [{:id "github-repo-id"
                                                       :name "test repo"
                                                       :ssh-url "ssh@ssh-url"
                                                       :clone-url "https://clone-url"}])
                                   (db/set-org {:repos [{:url "ssh@ssh-url"
                                                              :id "test-repo"
                                                              :github-id "github-repo-id"}]})))))
      (is (= "test-repo" (-> @r first :monkeyci/repo :id))))

    (testing "applies filter"
      (is (some? (swap! app-db db/set-ext-repo-filter "test")))
      (is (= 1 (count @r)))
      (is (some? (swap! app-db db/set-ext-repo-filter "other")))
      (is (empty? @r)))))

(deftest bitbucket-repos
  (let [r (rf/subscribe [:org/bitbucket-repos])]
    (testing "exists"
      (is (some? r)))

    (testing "returns repos from db"
      (let [l [{:uuid "test-repo"
                :name "test repo"
                :slug "test-repo"
                :workspace {:slug "test-ws"}}]]
        (is (map? (reset! app-db (bb/set-repos {} l))))
        (is (= ["test-repo"] (map :uuid @r)))))

    #_(testing "contains repo info"
      (is (map? (reset! app-db (-> {}
                                   (bb/set-repos [{:uuid "bb-repo-id"
                                                   :name "test repo"
                                                   :links {:html "http://test-url"}}])
                                   (db/set-org {:repos [{:url "ssh@ssh-url"
                                                              :id "test-repo"
                                                              :github-id "github-repo-id"}]})))))
      (is (= "test-repo" (-> @r first :monkeyci/repo :id))))

    (testing "marks watched repos"
      (is (some? (swap! app-db db/set-bb-webhooks [{:id "test-bb-webhook"
                                                    :webhook-id "test-webhook-id"
                                                    :workspace "test-ws"
                                                    :repo-slug "test-repo"
                                                    :bitbucket-id "test-bb-id"}])))
      (is (true? (:monkeyci/watched? (first @r)))))

    (testing "applies filter"
      (is (some? (swap! app-db db/set-ext-repo-filter "test")))
      (is (= 1 (count @r)))
      (is (some? (swap! app-db db/set-ext-repo-filter "other")))
      (is (empty? @r)))))

(deftest repo-alerts
  (let [a (rf/subscribe [:org/repo-alerts])]
    (testing "exists"
      (is (some? a)))

    (let [alert {:type :info :message "test alert"}]
      (testing "returns db alerts"
        (is (empty? @a))
        (is (some? (reset! app-db (db/set-repo-alerts {} [alert]))))
        (is (= [alert] @a)))

      (testing "contains github repo alerts"
        (let [gh-alert {:type :danger :message "test error"}]
          (is (some? (swap! app-db github/set-alerts [gh-alert])))
          (is (= [alert gh-alert] @a)))))))

(deftest create-alerts
  (let [s (rf/subscribe [:org/create-alerts])]
    (testing "exists"
      (is (some? s)))

    (testing "returns alerts from db"
      (let [a [{:type :info
                :message "Test alert"}]]
        (is (map? (reset! app-db (db/set-create-alerts {} a))))
        (is (= a @s))))))

(deftest org-creating?
  (let [l (rf/subscribe [:org/creating?])]
    (testing "exists"
      (is (some? l)))

    (testing "holds creating state from db"
      (is (not @l))
      (is (map? (reset! app-db (db/mark-org-creating {}))))
      (is (true? @l)))))

(deftest org-recent-builds
  (let [l (rf/subscribe [:org/recent-builds])]
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

    (testing "adds repo name from org info"
      (is (some? (reset! app-db (-> {}
                                    (lo/set-value db/recent-builds [{:id "test-build"
                                                                     :repo-id "test-repo"}])
                                    (db/set-org {:repos [{:id "test-repo"
                                                               :name "test repo name"}]})))))
      (is (= "test repo name" (-> @l first :repo :name))))))

(deftest org-stats
  (h/verify-sub [:org/stats] #(lo/set-value % db/stats ::test-stats) ::test-stats nil))

(deftest org-credits
  (h/verify-sub [:org/credits] #(lo/set-value % db/credits ::test-creds) ::test-creds nil))

(deftest credit-stats
  (h/verify-sub [:org/credit-stats]
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

(deftest org-labels
  (let [l (rf/subscribe [:org/labels])]
    (testing "exists"
      (is (some? l)))

    (testing "provides distinct labels for all org repos, sorted"
      (is (empty? @l))
      (is (some? (reset! app-db (db/set-org
                                 {}
                                 {:id "test-cust"
                                  :name "Test org"
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

(deftest org-group-by-lbl
  (h/verify-sub
   [:org/group-by-lbl]
   #(db/set-group-by-lbl % "test-lbl")
   "test-lbl"
   "project"))

(deftest org-grouped-repos
  (let [r (rf/subscribe [:org/grouped-repos])]
    (testing "exists"
      (is (some? r)))

    (let [[repo-1 repo-2 repo-3 :as repos]
          [{:id "repo-1"
            :name "test repo 1"
            :labels
            [{:name "label-1"
              :value "value 1"}
             {:name "label-2"
              :value "value 1"}]}
           {:id "repo-2"
            :name "test repo 2"
            :labels
            [{:name "label-2"
              :value "value 1"}]}
           {:id "repo-3"
            :name "test repo 3"
            :labels
            [{:name "label-2"
              :value "value 2"}]}]]
      (testing "returns repos, grouped by label from db"
        (is (some? (reset! app-db (-> {}
                                      (db/set-group-by-lbl "label-2")
                                      (db/set-org
                                       {:repos repos})))))
        (is (= {"value 1" [repo-1 repo-2]
                "value 2" [repo-3]}
               @r)))

      (testing "filters by repo name when repo filter specified"
        (is (some? (swap! app-db db/set-repo-filter "2")))
        (is (= {"value 1" [repo-2]} @r))))))

(deftest org-repo-filter
  (h/verify-sub
   [:org/repo-filter]
   #(db/set-repo-filter % "test-filter")
   "test-filter"
   nil))

(deftest org-ext-repo-filter
  (h/verify-sub
   [:org/ext-repo-filter]
   #(db/set-ext-repo-filter % "test-filter")
   "test-filter"
   nil))

(deftest bb-webhooks
  (h/verify-sub
   [:org/bb-webhooks]
   #(db/set-bb-webhooks % ::test-webhooks)
   ::test-webhooks
   nil))

(deftest latest-build
  (let [repo-id "test-repo"
        build ::test-build
        l (rf/subscribe [:org/latest-build repo-id])]
    (testing "exists"
      (is (some? l)))

    (testing "initially `nil`"
      (is (nil? @l)))

    (testing "returns latest build for repo"
      (is (some? (reset! app-db (db/set-latest-builds {}
                                                      {repo-id build}))))
      (is (= build @l)))))
