(ns monkey.ci.gui.martian
  (:require [martian.core :as martian]
            [martian.cljs-http :as mh]
            [martian.re-frame :as mr]
            [re-frame.core :as rf]
            [schema.core :as s]))

(def routes
  [{:route-name :get-customer
    :path-parts ["/customer/" :customer-id]
    :method :get
    :path-schema {:customer-id s/Str}
    :produces #{"application/edn"}}

   {:route-name :get-builds
    :path-parts ["/customer/" :customer-id "/project/" :project-id "/repo/" :repo-id "/builds"]
    :method :get
    :path-schema {:customer-id s/Str
                  :project-id s/Str
                  :repo-id s/Str}
    :produces #{"application/edn"}}

   {:route-name :get-build-logs
    :path-parts ["/customer/" :customer-id "/project/" :project-id "/repo/" :repo-id "/builds/" :build-id "/logs"]
    :method :get
    :path-schema {:customer-id s/Str
                  :project-id s/Str
                  :repo-id s/Str
                  :build-id s/Str}
    :produces #{"application/edn"}}])

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
