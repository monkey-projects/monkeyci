(ns monkey.ci.e2e.common
  (:require [aleph.http :as http]
            [config.core :refer [env]]
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

(defn request [method path]
  {:url (sut-url path)
   :method method})

(defn set-header [req k v]
  (assoc-in req [:headers k] v))

(def private-key
  "Returns the currently configured private key, used to sign tokens."
  (memoize
   (fn []
     (some-> (get env :monkeyci-private-key)
             (u/load-privkey)))))

(defn sysadmin-id []
  (get env :monkeyci-sysadmin-id))

(defn- sign-token [t]
  (wa/generate-and-sign-jwt t (private-key)))

(defn sysadmin-token
  "Generates new sysadmin token using the configured private key"
  []
  (-> (wa/sysadmin-token [wa/role-sysadmin (sysadmin-id)])
      (sign-token)))

(defn user-token
  "Creates authorization token for given user"
  [u]
  (-> (wa/user-token [(:type u) (:type-id u)])
      (sign-token)))

(defn set-token
  "Sets authorization token in the request"
  [req token]
  (set-header req "authorization" (str "Bearer " token)))

(defn set-body [req body]
  (let [s (pr-str body)]
    (-> req
        (assoc :body s)
        (set-header "content-length" (count s))
        (set-header "content-type" "application/edn"))))

(defn accept-edn [req]
  (set-header req "accept" "application/edn"))

(def parse-edn edn/edn->)

(defn try-parse-body [resp]
  (try
    (:body (wc/parse-body resp))
    (catch Exception ignored)))

(defn create-user
  "Creates a new random user and returns its details"
  []
  (-> {:url (sut-url "/user")
       :method :post}
      (set-token (sysadmin-token))
      (accept-edn)
      (set-body {:type "github"
                 :type-id (int (* (rand) 10000))})
      (http/request)
      (deref)
      (try-parse-body)))

(defn delete-user
  "Deletes given user, returns true if successful"
  [u]
  (= 204 (-> {:url (sut-url (str "/user/" (:id u)))
              :method :delete}
             (set-token (sysadmin-token))
             (http/request)
             (deref)
             :status)))
