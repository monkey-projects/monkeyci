(ns monkey.ci.gui.test.dashboard.login.events-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [clojure.string :as cs]
            [day8.re-frame.test :as rf-test]
            [monkey.ci.gui.dashboard.login.events :as sut]
            [monkey.ci.gui.dashboard.login.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :once f/dashboard-router)
(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest load-config
  (testing "fetches config according to provider"
    (rf-test/run-test-sync
     (let [e (h/catch-fx :martian.re-frame/request)]
       (h/initialize-martian {:get-github-config {:status 200
                                                  :body {}
                                                  :error-code :no-error}})
       (rf/dispatch [::sut/load-config :github])
       (is (= 1 (count @e)))
       (is (= :get-github-config (-> @e first (nth 2))))))))

(deftest oauth-login
  (let [r (sut/oauth-login {:db {}} [::sut/oauth-login :github])]
    (testing "redirects to auth url for provider"
      (is (some? (::sut/redirect r)))
      (is (re-matches #"^https://github.com/.*$" (-> r ::sut/redirect))))

    (testing "includes callback url"
      (let [[_ m] (re-matches #"^.*redirect_uri=(.+)$" (::sut/redirect r))]
        (is (some? m))
        (is (cs/ends-with? m (r/uri-encode "/oauth2/github/callback")))))))
