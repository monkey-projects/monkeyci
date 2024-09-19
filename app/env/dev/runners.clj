(ns runners
  (:require [babashka.fs :as fs]
            [config :as co]
            [monkey.ci
             [build :as b]
             [commands :as cmd]
             [runners]]
            [monkey.ci.web.auth :as auth]))

(defn clear-git-dir [build]
  (when-let [d (get-in build [:git :dir])]
    (when (fs/exists? d)
      (fs/delete-tree d))))

(defn gen-token [conf]
  (let [keypair (auth/config->keypair conf)
        ctx {:jwk keypair}
        token (auth/build-token (b/sid (:build conf)))]
    (auth/generate-jwt-from-rt ctx token)))

(defn- add-token [conf]
  (assoc-in conf [:api :token] (gen-token conf)))

(defn run-build-local
  "Runs the given build locally, using the global config.  This means that the local
   runner will be used, but it may run any container jobs or fetch/store artifacts,
   etc, as configured."
  [build]
  (let [dir (str (fs/create-temp-dir))
        conf (-> @co/global-config
                 (assoc-in [:runner :type] :child)
                 (assoc :build build
                        :dev-mode true
                        :checkout-base-dir dir)
                 (add-token))]
    (clear-git-dir build)
    ;; FIXME Doesn't work if the container sidecars can't connect to the build api server
    (cmd/run-build conf)))

(defn make-build [sid git-url branch]
  (-> (zipmap [:customer-id :repo-id :build-id] sid)
      (assoc :sid sid
             :git {:url git-url
                   :branch branch})))
