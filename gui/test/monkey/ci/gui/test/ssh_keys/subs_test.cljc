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

(deftest ssh-keys-loading?
  (h/verify-sub [:ssh-keys/loading?]
                db/set-loading
                true
                false))

(deftest ssh-keys-alerts
  (let [alerts [{:type :info}]]
    (h/verify-sub [:ssh-keys/alerts]
                  #(db/set-alerts % alerts)
                  alerts
                  nil)))

(deftest ssh-keys-keys
  (let [ssh-keys [{:description "test key"}]]
    (h/verify-sub [:ssh-keys/keys]
                  #(db/set-value % ssh-keys)
                  ssh-keys
                  nil)))
