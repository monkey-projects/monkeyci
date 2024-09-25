(ns monkey.ci.gui.artifact.subs
  (:require [monkey.ci.gui.artifact.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :artifact/alerts db/alerts)
(u/db-sub :artifact/downloading? db/downloading?)
