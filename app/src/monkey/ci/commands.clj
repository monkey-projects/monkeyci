(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]))

(defn- maybe-set-git-url [{{:keys [git-url]} :args :as ctx}]
  (cond-> ctx
    ;; TODO Also add branch and commit id
    git-url (assoc-in [:build :git :url] git-url)))

(defn build
  "Performs a build, using the runner from the context"
  [ctx]
  (let [r (:runner ctx)]
    (log/debug "Running build with runner" r)
    (-> ctx 
        ;; TODO Generate a more useful build id
        (assoc-in [:build :build-id] (format "build-%d" (System/currentTimeMillis)))
        (maybe-set-git-url)
        (r))))

(defn http-server
  "Does nothing but return a channel that will never close.  The http server 
   should already be started by the component system."
  [ctx]
  (ca/chan))
