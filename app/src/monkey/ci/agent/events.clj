(ns monkey.ci.agent.events
  "Agent event handlers.  These process `build/queued` events and start the build
   scripts in child container processes."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [build :as b]
             [process :as p]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]))

(defn set-config [ctx conf]
  (assoc ctx ::config conf))

(def get-config ::config)

(defn- build-work-dir [{:keys [work-dir]} sid]
  (str (apply fs/path work-dir sid)))

(defn- checkout-dir [wd]
  (str (fs/path wd "checkout")))

(defn- config-dir [wd]
  (str (fs/path wd "config")))

(defn- log-file [wd f]
  (str (fs/path wd "logs" f)))

(defn generate-deps [script-dir lib-version conf]
  (-> (p/generate-deps script-dir lib-version)
      (p/update-alias assoc :exec-args {:config conf})))

(defn generate-script-config [{:keys [api]} build]
  (-> sc/empty-config
      ;; TODO Set credit multiplier
      (sc/set-build build)
      ;; TODO This will only work if the container is in the host network
      ;; Ideally, we could use UDS but netty does not support this (yet)
      (sc/set-api {:url (format "http://localhost:" (:port api))
                   :token (:token api)})))

;;; Interceptors

(defn add-config [conf]
  {:name ::add-config
   :enter (fn [ctx]
            (set-config ctx conf))})

;;; Event handlers

(defn prepare-build-cmd [ctx]
  (let [conf (get-config ctx)
        build (get-in ctx [:event :build])
        wd (build-work-dir conf (get-in ctx [:event :sid]))
        sd (b/calc-script-dir (checkout-dir wd) (b/script-dir build))
        cd (config-dir wd)
        deps (generate-deps sd nil (generate-script-config conf build))]
    {:cmd ["podman"
           (:image conf)
           "clojure"
           "-Sdeps" (pr-str deps)
           "-X:monkeyci/build"]
     :dir sd
     :out (log-file wd "out.log")
     :err (log-file wd "err.log")
     :on-exit (fn [{:keys [exit]}]
                (log/info "Build container exited with code:" exit)
                (em/post-events (:mailman conf)
                                [(b/build-end-evt build exit)]))}))

;;; Routing

(defn make-routes [conf]
  (let [with-state (emi/with-state (atom {}))]
    [[:build/queued
      [{:handler prepare-build-cmd
        :interceptors [with-state
                       (add-config conf)
                       #_git-clone
                       #_upload-workspace
                       emi/start-process]}]]

     [:build/end
      [{:handler (constantly nil)
        :interceptors [with-state]}]]]))
