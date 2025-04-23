(ns monkey.ci.agent.test
  "Test functionality provided to debug the class not found issue (github #269)"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.agent
             [main :as am]
             [runtime :as ar]]
            [monkey.ci
             [config :as c]
             [edn :as edn]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runtime.common :as rc]))

(defn- clean-dir [d]
  (when (and d (fs/exists? d))
    (log/debug "Cleaning" d)
    (fs/delete-tree d)))

(defn run-test
  "Runs a test sequence by loading the given config and posting the specified
   event (supposedly a `build/queued` type).  Waits for `build/end` and then 
   exits."
  [opts]
  (let [conf (c/load-config-file (:config opts))
        evt (-> (:event opts)
                (slurp)
                (edn/edn->))
        cd (get-in conf [:agent :work-dir])]
    (clean-dir cd)
    (clean-dir (get-in conf [:containers :work-dir]))
    (am/run-agent 
     conf
     (fn [sys]
       (let [done (md/deferred)
             mm (:mailman sys)]
         (em/add-router mm
                        [[:build/end [{:handler (fn [evt]
                                                  (log/debug "Got build/end, terminating")
                                                  (md/success! done true)
                                                  nil)}]]]
                        {})
         (log/info "Posting event:" evt)
         (em/post-events mm [evt])
         (log/info "Waiting for :build/end...")
         done)))))
