(ns monkey.ci.git
  "Clone and checkout git repos.  This is mostly a wrapper for `clj-jgit`"
  (:require [babashka.fs :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log])
  (:import [org.eclipse.jgit.ignore IgnoreNode IgnoreNode$MatchResult]))

(defn- write-ssh-keys [dir idx r]
  (let [keys ((juxt :public-key :private-key) r)
        names (->> [".pub" ""]
                   (map (partial format "key-%d%s" idx)))
        paths (map (partial io/file dir) names)]
    (->> (map (fn [n k]
                (spit n k)
                (fs/set-posix-file-permissions n "rw-------"))
              paths keys)
         (doall))
    (merge r (zipmap [:public-key-file :private-key-file] (map str names)))))

(defn- list-private-keys
  "Given an existing directory, lists all private key file.  These are assumed to
   be all files that do not have a `.pub` extension."
  [dir]
  (letfn [(public? [f]
            (= "pub" (fs/extension f)))]
    (->> dir
         (fs/list-dir)
         (remove public?)
         (map fs/file-name))))

(defn prepare-ssh-keys
  "Writes any ssh keys in the options to a temp directory and returns their
   file names and key dir to be used by clj-jgit.  If an `ssh-keys-dir` is
   configured, but no `ssh-keys`, then it is assumed the keys are already 
   in place."
  [{:keys [ssh-keys ssh-keys-dir] :as conf}]
  (if (not-empty ssh-keys)
    (when-let [f (io/file ssh-keys-dir)]
      (when-not (or (.exists f) (.mkdirs f))
        (throw (ex-info "Unable to create ssh key dir" {:dir ssh-keys-dir})))
      (log/debug "Writing" (count ssh-keys) "ssh keys to" f)
      (->> ssh-keys
           (map-indexed (partial write-ssh-keys f))
           (doall)
           (mapv :private-key-file)
           (hash-map :key-dir ssh-keys-dir :name)))
    (when (and ssh-keys-dir (fs/exists? ssh-keys-dir))
      (log/debug "Found existing ssh keys dir, listing keys in it")
      {:key-dir ssh-keys-dir
       :name (list-private-keys ssh-keys-dir)})))

(def opts->branch (some-fn :ref :tag :branch))

(defn clone
  "Clones the repo at given url, and checks out the given branch.  Writes the
   files to `dir`.  Returns a repo object that can be passed to other functions."
  [{:keys [url dir] :as opts}]
  ;; Precalculate id config otherwise it gets called multiple times by the `with-identity` macro.
  (let [id-config (merge {:trust-all? true}
                         (prepare-ssh-keys opts))
        branch (opts->branch opts)]
    (git/with-identity id-config
      (log/debug "Cloning" url "and branch" branch "into" dir "with id config" id-config)
      (git/git-clone url
                     :branch branch
                     :dir dir))))

(defn checkout [repo id]
  (log/debug "Checking out" id "from repo" repo)
  (git/git-checkout repo {:name id}))

(defn clone+checkout
  "Clones the repo, then performs a checkout of the given commit id"
  [{:keys [commit-id] :as opts}]
  (let [repo (clone opts)]
    (when commit-id
      (checkout repo commit-id))
    repo))

(defn repo-work-tree
  "Gets the work tree location of the given repository"
  [repo]
  (.. repo
      (getRepository)
      (getWorkTree)))

(defn delete-repo
  "Deletes the previously checked out local repo"
  [repo]
  (log/debug "Deleting repository" repo)
  (-> repo
      (repo-work-tree)
      (fs/delete-tree)))

(defn- load-ignore
  "Loads the ignore file in the directory, returns an `IgnoreNode` with the
   loaded rules.  If no `.gitignore` file exists, the ruleset is empty."
  [dir]
  (let [p (fs/path dir ".gitignore")
        n (IgnoreNode.)]
    (when (fs/exists? p)
      (with-open [i (io/input-stream (fs/file p))]
        (.parse n i)))
    n))

(defn- ignored?
  "Checks if given path should be ignored, given the ignore rule chain."
  [p dir? chain]
  (loop [c (reverse chain)
         dir (fs/parent p)]
    (if (empty? c)
      false
      ;; The ignore node expects the path to be relative to the node location, so we relativize
      (let [r (.isIgnored (first c) (str (fs/relativize dir p)) dir?)]
        (condp = r
          IgnoreNode$MatchResult/IGNORED true
          IgnoreNode$MatchResult/NOT_IGNORED false
          ;; Other values: CHECK_PARENT, CHECK_PARENT_NEGATE_FIRST_MATCH but
          ;; unclear what the latter means and it's not even used in jgit code.
          (recur (rest c)
                 (fs/parent dir)))))))

(defn copy-with-ignore
  "Copies files from src to dest but skip any files matching the .gitignore files"
  ([src dest chain]
   (fs/create-dir dest)
   (let [{files false dirs true} (->> (fs/list-dir src)
                                      (group-by fs/directory?))]
     ;; First copy files, then descend into subdirs
     (doseq [p (->> files
                    (remove #(ignored? % false chain)))]
       (fs/copy p (fs/path dest (fs/file-name p))))
     (doseq [d (->> dirs
                    (remove #(ignored? % true chain)))]
       (copy-with-ignore d (fs/path dest (fs/file-name d)) (conj chain (load-ignore d)))))
   dest)
  ([src dest]
   (copy-with-ignore src dest [(load-ignore src)])))
