(ns monkey.ci.git
  "Clone and checkout git repos.  This is mostly a wrapper for `clj-jgit`"
  (:require [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [monkey.ci.utils :as u]))

(defn clone
  "Clones the repo at given url, and checks out the given branch.  Writes the
   files to `dir`.  Returns a repo object that can be passed to other functions."
  [url branch dir]
  (log/debug "Cloning" url "into" dir)
  (git/git-clone url :branch branch :dir dir))

(defn checkout [repo id]
  (log/debug "Checking out" id "from repo" repo)
  (git/git-checkout repo {:name id}))

(defn clone+checkout
  "Clones the repo, then performs a checkout of the given id"
  [url branch id dir]
  (doto (clone url branch dir)
    (checkout id)))

(defn delete-repo
  "Deletes the previously checked out local repo"
  [repo]
  (log/debug "Deleting repository" repo)
  (-> repo
      (.getRepository)
      (.getWorkTree)
      (u/delete-dir)))
