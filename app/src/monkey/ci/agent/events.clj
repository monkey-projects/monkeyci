(ns monkey.ci.agent.events
  "Agent event handlers.  These process `build/queued` events and start the build
   scripts in child container processes."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [process :as p]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]))

(defn set-config [ctx conf]
  (assoc ctx ::config conf))

(def get-config ::config)

(defn set-token [ctx token]
  (assoc ctx ::token token))

(def get-token ::token)

(def get-build (comp :build :event))

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

(defn generate-script-config [ctx]
  (-> sc/empty-config
      ;; TODO Set credit multiplier
      (sc/set-build (get-build ctx))
      ;; TODO This will only work if the container is in the host network
      ;; Ideally, we could use UDS but netty does not support this (yet)
      (sc/set-api {:url (format "http://localhost:" (-> ctx (get-config) :api-server :port))
                   :token (get-token ctx)})))

;;; Interceptors

(defn add-config [conf]
  {:name ::add-config
   :enter (fn [ctx]
            (set-config ctx conf))})

(def add-token
  "Generates a new token and adds it to the current build list.  This is used by the
   http server to verify client requests."
  {:name ::add-token
   :enter (fn [ctx]
            (let [token (bas/generate-token)]
              (swap! (:builds (get-config ctx)) assoc token (get-build ctx))
              (set-token ctx token)))})

(def remove-token
  "Removes the token associated with the build in the event sid"
  {:name ::remove-token
   :leave (fn [ctx]
            (swap! (:builds (get-config ctx)) (partial mc/filter-vals
                                                       (complement
                                                        (comp (partial = (get-in ctx [:event :sid]))
                                                              b/sid))))
            ctx)})

(def git-clone
  "Clones the repository configured in the build into the build checkout dir"
  {:name ::git-clone
   :enter (fn [ctx]
            (let [conf (get-config ctx)
                  clone (-> conf :git :clone)
                  build (get-build ctx)
                  opts (-> build
                           :git
                           (assoc :dir (checkout-dir (build-work-dir conf (get-in ctx [:event :sid])))))]
              (assoc ctx ::checkout-dir (clone opts))))})

(def save-workspace
  {:name ::save-workspace
   :enter (fn [ctx]
            (assoc ctx ::build (-> (get-build ctx)
                                   (b/set-checkout-dir (::checkout-dir ctx))
                                   (ws/create-workspace (get-config ctx)))))})

(def result-build-init-evt
  {:name ::result-build-init-evt
   :leave (fn [ctx]
            (assoc ctx :result [(b/build-init-evt (::build ctx))]))})

;;; Event handlers

(defn prepare-build-cmd [ctx]
  (let [conf (get-config ctx)
        build (get-build ctx)
        wd (build-work-dir conf (get-in ctx [:event :sid]))
        sd (b/calc-script-dir (checkout-dir wd) (b/script-dir build))
        cd (config-dir wd)
        deps (generate-deps sd nil (generate-script-config ctx))]
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
  [[:build/queued
    [{:handler prepare-build-cmd
      :interceptors [(add-config conf)
                     add-token
                     git-clone
                     save-workspace
                     result-build-init-evt
                     emi/start-process]}]]

   [:build/end
    [{:handler (constantly nil)
      :interceptors [(add-config conf)
                     remove-token]}]]])
