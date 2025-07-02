(ns monkey.ci.web.api.ssh-keys
  (:require [monkey.ci.storage :as st]
            [monkey.ci.web
             [common :as c]
             [crypto :as cr]]))

(defn- update-pk [f ssh-key]
  (update ssh-key :private-key f))

(defn- encrypt [encrypter ssh-key]
  (update-pk encrypter ssh-key))

(defn- decrypt [decrypter ssh-key]
  (update-pk decrypter ssh-key))

(defn- encrypt-all [req ssh-keys]
  (map (partial encrypt (cr/encrypter req)) ssh-keys))

(defn- decrypt-all [req ssh-keys]
  (map (partial decrypt (cr/decrypter req)) ssh-keys))

(defn get-org-ssh-keys [req]
  (c/get-list-for-org (comp (partial decrypt-all req) c/drop-ids st/find-ssh-keys) req))

(defn get-repo-ssh-keys [req]
  (c/get-for-repo-by-label (comp c/drop-ids st/find-ssh-keys)
                           (comp (map (partial decrypt (cr/decrypter req)))
                                 (map :private-key))
                           req))

(defn update-ssh-keys [req]
  (letfn [(encrypt-and-save [st org-id ssh-keys]
            (->> (encrypt-all req ssh-keys)
                 (st/save-ssh-keys st org-id)))]
    (c/update-for-org encrypt-and-save req)))
