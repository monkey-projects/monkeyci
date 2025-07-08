(ns monkey.ci.web.trigger
  (:require [buddy.core.codecs :as bcc]
            [monkey.ci
             [labels :as lbl]
             [storage :as st]
             [time :as t]]
            [monkey.ci.web.crypto :as crypto]))

(defn find-ssh-keys
  "Finds ssh keys for the given repo, in encrypted form."
  [st {:keys [org-id] :as repo}]
  (->> (st/find-ssh-keys st org-id)
       (lbl/filter-by-label repo)))

(defn- prepare-git
  "Prepares the git configuration for the build"
  [build rt {:keys [org-id] :as repo} dek]
  (let [d (get-in rt [:crypto :decrypter])]
    (letfn [(set-branch [g]
              (update g :ref #(or % (some->> (:main-branch repo) (str "refs/heads/")))))
            (re-encrypt [id x]
              (-> x
                  (d org-id id)
                  ;; Re-encrypt ssh keys using DEK
                  (as-> v (crypto/encrypt dek (crypto/cuid->iv org-id) v))))
            (prepare-key [k]
              (-> (select-keys k [:private-key :public-key])
                  (update :private-key (partial re-encrypt (:id k)))))
            ;; TODO Remove this, keys are fetched using api by runners instead
            (add-ssh [g]
              (let [k (->> (find-ssh-keys (:storage rt) repo)
                           (map prepare-key))]
                (cond-> g
                  (not-empty k) (assoc :ssh-keys k))))]
      (update build :git (comp add-ssh set-branch)))))

(defn prepare-triggered-build
  "Prepares the triggered build using the given initial build information."
  [{:keys [org-id repo-id] :as init-build} rt & [repo]]
  (let [repo (or repo (st/find-repo (:storage rt) [org-id repo-id]))
        dek (crypto/generate-build-dek rt org-id)]
    (-> init-build
        (assoc :id (st/new-id)
               ;; Do not use the commit timestamp, because when triggered from a tag
               ;; this is still the time of the last commit, not of the tag creation.
               :start-time (t/now)
               :status :pending
               :dek (:enc dek))
        (dissoc :message)
        (prepare-git rt repo (bcc/b64->bytes (:key dek))))))
