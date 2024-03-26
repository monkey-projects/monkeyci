(ns monkey.ci.git
  "Clone and checkout git repos.  This is mostly a wrapper for `clj-jgit`"
  (:require [babashka.fs :as fs]
            [clj-jgit.porcelain :as git]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [runtime :as rt]
             [utils :as u]]))

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
           (hash-map :key-dir ssh-keys-dir :name)))
    conf))

(def opts->branch (some-fn :ref :branch))

(defn clone
  "Clones the repo at given url, and checks out the given branch.  Writes the
   files to `dir`.  Returns a repo object that can be passed to other functions."
  [{:keys [url dir] :as opts}]
  ;; Precalculate id config otherwise it gets called multiple times by the `with-identity` macro.
  (let [id-config (merge {:trust-all? true}
                         (prepare-ssh-keys opts))
        branch (opts->branch opts)]
    (git/with-identity id-config
      (log/debug "Cloning" url "and branch" branch "into" dir)
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

(defn delete-repo
  "Deletes the previously checked out local repo"
  [repo]
  (log/debug "Deleting repository" repo)
  (-> repo
      (.getRepository)
      (.getWorkTree)
      (u/delete-dir)))

(defmethod rt/setup-runtime :git [_ _]
  {:clone (fn default-git-clone [opts]
            (clone+checkout opts)
            ;; Return the checkout dir
            (:dir opts))})
