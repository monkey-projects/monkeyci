(ns monkey.ci.gui.ssh-keys.subs
  (:require [monkey.ci.gui.ssh-keys.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :ssh-keys/loading? db/loading?)
(u/db-sub :ssh-keys/alerts db/get-alerts)
(u/db-sub :ssh-keys/keys db/get-value)
