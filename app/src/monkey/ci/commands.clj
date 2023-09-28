(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [monkey.ci.utils :as u]))

(defn- maybe-set-git-opts [{{:keys [git-url branch commit-id]} :args :as ctx}]
  (cond-> ctx
    git-url (assoc-in [:build :git] {:url git-url
                                     :branch (or branch "main")
                                     :id commit-id})))

(defn build
  "Performs a build, using the runner from the context"
  [ctx]
  (let [r (:runner ctx)]
    (-> ctx 
        (assoc-in [:build :build-id] (u/new-build-id))
        (maybe-set-git-opts)
        (r))))

(defn http-server
  "Does nothing but return a channel that will never close.  The http server 
   should already be started by the component system."
  [ctx]
  (ca/chan))
