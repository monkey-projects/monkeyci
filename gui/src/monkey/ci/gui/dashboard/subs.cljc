(ns monkey.ci.gui.dashboard.subs
  (:require [monkey.ci.gui.dashboard.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 ::recent-builds
 :<- [:loader/value db/recent-builds]
 (fn [r _]
   r))
