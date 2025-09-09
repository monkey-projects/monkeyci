(ns monkey.ci.e2e.common
  (:require [config.core :refer [env]]
            [monkey.ci
             [edn :as edn]
             [utils :as u]]
            [monkey.ci.web
             [auth :as wa]
             [common :as wc]]))

(defn sut-url
  "Constructs url for system under test with given path.  The url depends
   on env vars and app settings."
  [path]
  (str (get env :monkeyci-url "http://localhost:3000") path))

(def private-key
  "Returns the currently configured private key, used to sign tokens."
  (memoize
   (fn []
     (some-> (get env :monkeyci-private-key)
             (u/load-privkey)))))

(defn sysadmin-id []
  (get env :monkeyci-sysadmin-id))

(defn sysadmin-token
  "Generates new sysadmin token using the configured private key"
  []
  (-> (wa/sysadmin-token [wa/role-sysadmin (sysadmin-id)])
      (wa/generate-and-sign-jwt (private-key))))

(defn set-token
  "Sets authorization token in the request"
  [req token]
  (assoc-in req [:headers "authorization"] (str "Bearer " token)))

(defn set-body [req body]
  (let [s (pr-str body)]
    (-> req
        (assoc :body s)
        (assoc-in [:headers "content-length"] (count s))
        (assoc-in [:headers "content-type"] "application/edn"))))

(defn accept-edn [req]
  (assoc-in req [:headers "accept"] "application/edn"))

(def parse-edn edn/edn->)

(defn try-parse-body [resp]
  (try
    (:body (wc/parse-body resp))
    (catch Exception ignored)))
