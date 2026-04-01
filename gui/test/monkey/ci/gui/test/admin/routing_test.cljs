(ns monkey.ci.gui.test.admin.routing-test
  (:require [cljs.test :refer-macros [deftest testing is use-fixtures async]]
            [monkey.ci.gui.admin.routing :as sut]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]))

(use-fixtures :once f/admin-router)

(deftest public?
  (testing "`true` if route is publicly accessible"
    (is (r/public? (r/match-by-name :admin/login {}))))

  (testing "`false` if route is not publicly accessible"
    (is (not (r/public? (r/match-by-name :admin/root {}))))))
