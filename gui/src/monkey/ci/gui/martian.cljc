(ns monkey.ci.gui.martian
  (:require [martian.core :as martian]
            [martian.cljs-http :as mh]
            [martian.re-frame :as mr]
            [re-frame.core :as rf]
            [schema.core :as s]))

(def customer-path ["/customer/" :customer-id])
(def repo-path (into customer-path ["/repo/" :repo-id]))
(def build-path (into repo-path ["/builds/" :build-id]))

(def customer-schema
  {:customer-id s/Str})

(def repo-schema
  (assoc customer-schema
         :repo-id s/Str))

(def build-schema
  (assoc repo-schema :build-id s/Str))

(defn api-route [conf]
  (merge {:method :get
          :produces #{"application/edn"}}
         conf))

(def routes
  [(api-route
    {:route-name :get-customer
     :path-parts customer-path
     :path-schema customer-schema})

   (api-route
    {:route-name :get-builds
     :path-parts (into repo-path ["/builds"])
     :path-schema repo-schema})

   (api-route
    {:route-name :get-build
     :path-parts build-path
     :path-schema build-schema})

   (api-route
    {:route-name :get-build-logs
     :path-parts (into build-path ["/logs"])
     :path-schema build-schema})

   {:route-name :github-login
    :method :post
    :path-parts ["/github/login"]
    :query-schema {:code s/Str}
    :consumes #{"application/json"}
    :produces #{"application/json"}}])

;; The api url.  This should be configured in a `config.js`.
(def url #?(:clj "http://localhost:3000"
            :cljs (if (exists? js/apiRoot)
                    js/apiRoot
                    "http://test:3000")))

(def disable-with-credentials
  "Interceptor that explicitly sets the `with-credentials?` property in the request
   to `false`.  If this property is not provided, then `cljs-http` will enable it on
   the outgoing request.  This in turn causes CORS issues."
  {:enter (fn [ctx]
            (assoc-in ctx [:request :with-credentials?] false))})

(defn init
  "Initializes using the fixed routes"
  []
  (println "Initializing" (count routes) "routes")
  (rf/dispatch-sync
   [::mr/init (martian/bootstrap
               url
               routes
               {:interceptors (concat martian/default-interceptors
                                      [disable-with-credentials
                                       mh/perform-request])})]))
