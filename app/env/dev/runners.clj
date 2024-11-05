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
  "Runs the given build locally, using the given config.  This means that the local
   runner will be used, but it may run any container jobs or fetch/store artifacts,
   etc, as configured."
  ([conf build]
   (let [dir (str (fs/create-temp-dir))
         conf (-> conf
                  (assoc-in [:runner :type] :local)
                  (assoc :build build
                         :checkout-base-dir dir)
                  (add-token))]
     (clear-git-dir build)
     ;; Note that this doesn't work if the container sidecars can't connect to the build api server
     ;; so we can't use oci container runners here.
     (cmd/run-build conf)))
  ([build]
   (run-build-local @co/global-config build)))

(defn make-build [sid git-url branch]
  (-> (zipmap [:customer-id :repo-id :build-id] sid)
      (assoc :sid sid
             :git {:url git-url
                   :branch branch})))

(defn run-example-local
  "Runs an example build locally by starting the build process.  Containers are run using podman."
  [conf example]
  (let [build (-> ["example-cust" "example-repo" (str "build-" (System/currentTimeMillis))]
                  (make-build nil nil)
                  (dissoc :git)
                  (assoc-in [:script :script-dir] (str (fs/absolutize (fs/path "examples/" example)))))
        conf (-> conf
                 (assoc :containers {:type :podman}))]
    (run-build-local build)))
