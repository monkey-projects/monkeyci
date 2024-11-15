(ns monkey.ci.gui.ssh-keys.subs
  (:require [monkey.ci.gui.ssh-keys.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :ssh-keys/loading? db/loading?)
(u/db-sub :ssh-keys/alerts db/get-alerts)
(u/db-sub :ssh-keys/keys db/get-value)
(u/db-sub :ssh-keys/editing-keys db/get-editing-keys)

(rf/reg-sub
 ;; Retrieves the editing version of a given ssh key, or `nil` if the
 ;; key is not being edited.
 :ssh-keys/editing
 :<- [:ssh-keys/editing-keys]
 (fn [ed [_ k]]
   (let [get-key (juxt :id :temp-id)
         by-id (group-by get-key ed)]
     (some-> (get by-id (get-key k))
             first))))

(rf/reg-sub
 :ssh-keys/display-keys
 :<- [:ssh-keys/keys]
 :<- [:ssh-keys/editing-keys]
 (fn [[orig ed] _]
   (let [by-id (group-by :id ed)
         new? (comp some? :temp-id)
         mark-editing #(assoc % :editing? true)]
     (-> (map (fn [k]
                (or (some-> (get by-id (:id k))
                            first
                            (mark-editing))
                    k))
              orig)
         (concat (->> (filter new? ed)
                      (map mark-editing)))))))
