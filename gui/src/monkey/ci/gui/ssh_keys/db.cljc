(ns monkey.ci.gui.ssh-keys.db
  (:require [monkey.ci.gui.loader :as lo]))

(def id ::ssh-keys)

(def set-loading #(lo/set-loading % id))
(def loading? #(lo/loading? % id))

(def set-alerts #(lo/set-alerts %1 id %2))
(def get-alerts #(lo/get-alerts % id))
(def reset-alerts #(lo/reset-alerts % id))

(def set-value #(lo/set-value %1 id %2))
(def get-value #(lo/get-value % id))

(def editing-keys ::editing-keys)

(def get-editing-keys editing-keys)

(def set-id (some-fn :temp-id :id))
(def new-set? (comp some? :temp-id))

(defn set-editing-keys [db k]
  (assoc db editing-keys k))

(defn update-editing-keys [db f & args]
  (apply update db editing-keys f args))

(defn get-new-keys [db]
  (filter new-set? (get-editing-keys db)))

(defn same-id? [id]
  (comp (partial = id) set-id))

(defn update-editing-key
  "Updates a single ssh key by id"
  [db id f & args]
  (update-editing-keys db (fn [keys]
                            (let [existing (->> keys
                                                (filter (same-id? id))
                                                (first))]
                              (replace {existing (apply f existing args)} keys)))))
