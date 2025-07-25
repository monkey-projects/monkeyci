(ns monkey.ci.gui.test.helpers
  (:require [cljs.test :refer-macros [testing is]]
            [martian.core :as martian]
            [martian.test :as mt]
            [monkey.ci.gui.martian :as mm]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(defn catch-fx [fx]
  (let [inv (atom [])]
    (rf/reg-fx fx (fn [evt]
                    (swap! inv conj evt)))
    inv))

(defn initialize-martian
  "Initializes the martian client with given responses.  
   Use this in a `re-frame.test/run-test-async`"
  [responses]
  (let [m (-> (martian/bootstrap mm/url mm/routes)
              (mt/respond-as "cljs-http")
              (mt/respond-with responses))]
    (rf/dispatch [:martian.re-frame/init m])))

(defn verify-sub
  "Runs basic verifications agains a sub"
  [sub setter exp-val default-val]
  (testing (str "sub " sub)
    (let [s (rf/subscribe sub)]
      (testing "exists"
        (is (some? s)))

      (when (some? default-val)
        (testing "has default value"
          (is (= default-val @s))))

      (testing "returns expected value"
        (is (some? (reset! app-db (setter {}))))
        (is (= exp-val @s))))))

(defn set-repo-path [db org repo]
  (r/set-current db {:parameters
                     {:path 
                      {:org-id org
                       :repo-id repo}}}))

(defn set-repo-path! [org repo]
  (swap! app-db set-repo-path org repo))
