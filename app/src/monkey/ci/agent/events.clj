(ns monkey.ci.agent.events
  "Agent event handlers.  These process `build/queued` events and start the build
   scripts in child container processes."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [process :as p]
             [time :as t]
             [workspace :as ws]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]))

(def m2-cache-path
  "Location of the m2 cache path in the container"
  "/opt/monkeyci/m2")

(defn set-config [ctx conf]
  (assoc ctx ::config conf))

(def get-config ::config)

(defn set-token [ctx token]
  (assoc ctx ::token token))

(def get-token ::token)

(def get-build (comp :build :event))

(def get-ssh-keys ::ssh-keys)

(defn set-ssh-keys [ctx k]
  (assoc ctx ::ssh-keys k))

(defn- build-work-dir [{:keys [work-dir]} sid]
  (str (apply fs/path work-dir sid)))

(defn- ctx->wd [ctx]
  (build-work-dir (get-config ctx) (get-in ctx [:event :sid])))

(defn- checkout-dir [wd]
  (str (fs/create-dirs (fs/path wd "checkout"))))

(defn- m2-cache [wd]
  (str (fs/create-dirs (fs/path wd "m2"))))

(defn- config-dir [wd]
  (str (fs/path wd "config")))

(defn- log-dir [wd]
  (fs/create-dirs (fs/path wd "logs")))

(defn- log-file [wd f]
  (str (fs/path (log-dir wd) f)))

(defn- ssh-keys-dir [wd]
  (fs/create-dirs (fs/path wd "ssh")))

(defn generate-deps [script-dir lib-version conf]
  (-> (p/generate-deps script-dir lib-version)
      (p/update-alias assoc :exec-args {:config conf})
      (assoc :mvn/local-repo m2-cache-path)))

(defn generate-script-config [ctx]
  (-> sc/empty-config
      ;; TODO Set credit multiplier
      (sc/set-build (get-build ctx))
      ;; TODO This will only work if the container is in the host network
      ;; Ideally, we could use UDS but netty does not support this (yet)
      (sc/set-api {:url (str "http://localhost:" (-> ctx (get-config) :api-server :port))
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

(defn fetch-ssh-keys [fetcher]
  {:name ::fetch-ssh-keys
   :enter (fn [ctx]
            (set-ssh-keys ctx (fetcher (get-in ctx [:event :sid]))))})

(def git-clone
  "Clones the repository configured in the build into the build checkout dir"
  {:name ::git-clone
   :enter (fn [ctx]
            (let [conf (get-config ctx)
                  clone (-> conf :git :clone)
                  build (get-build ctx)
                  wd (build-work-dir conf (get-in ctx [:event :sid]))
                  opts (-> build
                           :git
                           (assoc :dir (checkout-dir wd)
                                  :ssh-keys-dir (str (ssh-keys-dir wd))
                                  ;; Do not pass the ssh keys we receive in the event, because
                                  ;; they are encrypted.  Instead, fetch keys from api.
                                  :ssh-keys (->> (get-ssh-keys ctx)
                                                 (map (partial hash-map :private-key)))))]
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
        lwd "/home/monkeyci"
        checkout (checkout-dir wd)
        script-dir #(b/calc-script-dir % (b/script-dir build))
        sd (script-dir checkout)
        cd (config-dir wd)
        deps (generate-deps sd (:version conf) (generate-script-config ctx))]
    {:cmd ["podman"
           "run"
           "--name" (str (:build-id build) "-" (t/now))
           "--rm"
           "-v" (str checkout ":" lwd ":Z")
           ;; m2 cache is common for the all repo builds
           "-v" (str (m2-cache (fs/parent wd)) ":" m2-cache-path ":Z")
           "--workdir" (script-dir lwd)
           "--network=host"
           (:image conf)
           "clojure"
           "-Sdeps" (pr-str deps)
           "-X:monkeyci/build"]
     :dir sd
     :out (log-file wd "out.log")
     :err (log-file wd "err.log")
     :exit-fn (p/exit-fn
               (fn [{:keys [exit]}]
                 ;; TODO Clean up build files
                 (log/info "Build container exited with code:" exit)
                 (em/post-events (:mailman conf)
                                 [(b/build-end-evt build exit)])))}))

(defn script-init [conf ctx]
  ;; Fire build/start.  We don't really have a way to determine the process has
  ;; started, except for capturing script/initializing event, so we use that to fire
  ;; the build/start.
  [(b/build-start-evt (-> conf
                          (select-keys [:credit-multiplier])
                          (assoc :sid (get-in ctx [:event :sid]))))])

;;; Routing

(defn make-routes [conf]
  [[:build/queued
    [{:handler prepare-build-cmd
      ;; TODO Restore build cache
      :interceptors [emi/handle-build-error
                     (add-config conf)
                     add-token
                     (fetch-ssh-keys (:ssh-keys-fetcher conf))
                     git-clone
                     save-workspace
                     result-build-init-evt
                     emi/start-process]}]]

   [:script/initializing
    [{:handler (partial script-init conf)}]]

   [:build/end
    [{:handler (constantly nil)
      ;; TODO Save build cache
      :interceptors [(add-config conf)
                     remove-token]}]]])
