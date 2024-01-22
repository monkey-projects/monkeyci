(ns monkey.ci.git
  "Clone and checkout git repos.  This is mostly a wrapper for `clj-jgit`"
  (:require [babashka.fs :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.utils :as u]))

(defn prepare-ssh-keys
  "Writes any ssh keys in the options to a temp directory and returns their
   file names and key dir to be used by clj-jgit."
  [{:keys [ssh-keys ssh-keys-dir]}]
  (when-let [f (io/file ssh-keys-dir)]
    (when-not (or (.exists f) (.mkdirs f))
      (throw (ex-info "Unable to create ssh key dir" {:dir ssh-keys-dir})))
    (log/debug "Writing" (count ssh-keys) "ssh keys to" f)
    (->> ssh-keys
         (map-indexed (fn [idx r]
                        (let [keys ((juxt :public-key :private-key) r)
                              names (->> [".pub" ""]
                                         (map (partial format "key-%d%s" idx)))
                              paths (map (partial io/file f) names)]
                          (->> (map (fn [n k]
                                      (spit n k)
                                      (fs/set-posix-file-permissions n "rw-------"))
                                    paths keys)
                               (doall))
                          (merge r (zipmap [:public-key-file :private-key-file] (map str names))))))
         (doall)
         (mapv :private-key-file)
         (hash-map :key-dir ssh-keys-dir :name))))

(defn clone
  "Clones the repo at given url, and checks out the given branch.  Writes the
   files to `dir`.  Returns a repo object that can be passed to other functions."
  [{:keys [url branch dir] :as opts}]
  (log/debug "Cloning" url "into" dir)
  (git/with-identity (merge {:trust-all? true}
                            (prepare-ssh-keys opts))
    (git/git-clone url
                   :branch branch
                   :dir dir
                   :no-checkout? true)))

(defn checkout [repo id]
  (log/debug "Checking out" id "from repo" repo)
  (git/git-checkout repo {:name id}))

(def origin-prefix "origin/")

(defn- prefix-origin
  "Ensures that the given branch name has the `origin` prefix"
  [b]
  (when b
    (cond->> b
      (not (cs/starts-with? b origin-prefix)) (str origin-prefix))))

(defn clone+checkout
  "Clones the repo, then performs a checkout of the given id"
  [{:keys [branch id] :as opts}]
  (let [repo (clone opts)]
    (when-let [id-or-branch (or id (prefix-origin branch))]
      (checkout repo id-or-branch))
    repo))

(defn delete-repo
  "Deletes the previously checked out local repo"
  [repo]
  (log/debug "Deleting repository" repo)
  (-> repo
      (.getRepository)
      (.getWorkTree)
      (u/delete-dir)))
