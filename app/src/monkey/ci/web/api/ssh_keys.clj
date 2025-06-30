(ns monkey.ci.web.api.ssh-keys
  (:require [monkey.ci
             [protocols :as p]
             [storage :as st]]
            [monkey.ci.web.common :as c]))

(defn- update-pk [req f iv ssh-key]
  (update ssh-key :private-key (partial f (c/req->vault req) iv)))

(defn- encrypt [req iv ssh-key]
  (update-pk req p/encrypt iv ssh-key))

(defn- decrypt [req iv ssh-key]
  (update-pk req p/decrypt iv ssh-key))

(defn- encrypt-all [req ssh-keys]
  (let [iv (c/crypto-iv req)]
    (map (partial encrypt req iv) ssh-keys)))

(defn- decrypt-all [req ssh-keys]
  (let [iv (c/crypto-iv req)]
    (map (partial decrypt req iv) ssh-keys)))

(defn get-org-ssh-keys [req]
  (c/get-list-for-org (comp (partial decrypt-all req) c/drop-ids st/find-ssh-keys) req))

(defn get-repo-ssh-keys [req]
  (let [iv (c/crypto-iv req)]
    (c/get-for-repo-by-label (comp c/drop-ids st/find-ssh-keys)
                             (comp (map (partial decrypt req iv))
                                   (map :private-key))
                             req)))

(defn update-ssh-keys [req]
  (letfn [(encrypt-and-save [st org-id ssh-keys]
            (->> (encrypt-all req ssh-keys)
                 (st/save-ssh-keys st org-id)))]
    (c/update-for-org encrypt-and-save req)))
