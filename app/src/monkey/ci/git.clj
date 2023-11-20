(ns monkey.ci.git
  "Clone and checkout git repos.  This is mostly a wrapper for `clj-jgit`"
  (:require [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci.utils :as u]))

(defn clone
  "Clones the repo at given url, and checks out the given branch.  Writes the
   files to `dir`.  Returns a repo object that can be passed to other functions."
  [url branch dir]
  (log/debug "Cloning" url "into" dir)
  (git/with-identity {:trust-all? true}
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
  [url branch id dir]
  (let [repo (clone url branch dir)]
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
