(ns monkey.ci.entities.org-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [core :as c]
             [org :as sut]
             [helpers :as h]]))

(deftest ^:sql org-with-repos
  (testing "returns org and its repos"
    (h/with-prepared-db conn
      (let [org (c/insert-org conn {:name "test org"})
            repos (->> (range 3)
                       (map #(c/insert-repo conn {:org-id (:id org)
                                                  :display-id (str "repo-" %)
                                                  :name (str "repo-" %)}))
                       (doall))
            match (sut/org-with-repos conn (c/by-cuid (:cuid org)))]
        (is (some? match))
        (is (= (:id org) (:id match)))
        (is (map? (:repos match)))
        (is (= (count repos) (count (:repos match))))))))
