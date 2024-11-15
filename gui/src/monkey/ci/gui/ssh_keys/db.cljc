(ns monkey.ci.gui.ssh-keys.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::ssh-keys)

(def set-loading #(lo/set-loading % id))
(def loading? #(lo/loading? % id))

(def set-alerts #(lo/set-alerts %1 id %2))
(def get-alerts #(lo/get-alerts % id))

(def set-value #(lo/set-value %1 id %2))
(def get-value #(lo/get-value % id))
