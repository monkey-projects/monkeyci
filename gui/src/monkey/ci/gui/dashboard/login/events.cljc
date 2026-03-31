(ns monkey.ci.gui.dashboard.login.events
  (:require [re-frame.core :as rf]
            [day8.re-frame.http-fx]
            [ajax.core :refer [json-request-format
                               json-response-format]]
            [monkey.ci.gui.dashboard.login.db :as db]
            [monkey.ci.gui.dashboard.login.http :as http]
            [monkey.ci.gui.local-storage :as l]
            ;; Reusing the original login events for this
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.login.events :as levt]
            [monkey.ci.gui.routing :as r]
            [clojure.string :as str]))

(def token-id "mci_token")

(def provider-configs
  {:github
   {:oauth-url "https://github.com/login/oauth/authorize"
    :config-load :login/load-github-config
    :extra-params {"response_type" "code"}}})

(rf/reg-event-fx
 ::load-config
 (fn [_ [_ provider]]
   (when-let [e (get-in provider-configs [provider :config-load])]
     {:dispatch [e]})))

(rf/reg-event-fx
 ::oauth-login
 (fn [{:keys [db]} [_ provider]]
   (let [c (get provider-configs provider)
         callback-url (str (r/origin) (r/path-for :page/oauth-callback {:provider (name provider)}))]
     (println "Callback url:" callback-url)
     (println "Redirecting to:" (:oauth-url c))
     {:redirect (str (:oauth-url c) "?" (ldb/build-auth-params (db/get-client-id db provider)
                                                               callback-url
                                                               (:extra-params c)))})))

;; Called by the backend redirect after successful OAuth
(rf/reg-event-fx
  ::oauth-callback-success
  (fn [{:keys [db]} [_ code]]
    {:dispatch [:login/github-code-received code]}
    #_{:db (assoc db :token token :user user)
       :fx [[:local-storage [token-id token]]
            [:navigate-to (str "/" workspace "/overview")]]}))

;; ── Refresh token ──────────────────────────────────────────────
;;
;; Call this on app boot if a token exists in localStorage —
;; the server returns a fresh short-lived access token.

(rf/reg-event-fx
 :refresh-token
 (fn [{:keys [db]} [_ stored-token]]
   {:db         (assoc db :loading? true)
    :http-xhrio {:method          :post
                 :uri             (http/endpoint "/auth/token/refresh")
                 :headers         (http/with-auth
                                    http/default-headers
                                    stored-token)
                 :format          (json-request-format)
                 :response-format (json-response-format {:keywords? true})
                 :on-success      [:token-refreshed]
                 :on-failure      [:token-expired]
                 :timeout         5000}}))

(rf/reg-event-fx
  :token-refreshed
  (fn [{:keys [db]} [_ {:keys [token]}]]
    {:db (assoc db :loading? false :token token)
     :fx [[:local-storage [token-id token]]]}))

(rf/reg-event-fx
  :token-expired
  (fn [{:keys [db]} _]
    {:db (assoc db :loading? false :token nil)
     :fx [[:local-storage/remove token-id]
          [:navigate-to "/login"]]}))

;; ── Custom effects ─────────────────────────────────────────────
;;
;; These keep side-effectful operations (localStorage, navigation)
;; out of event handlers and therefore easily testable.

(rf/reg-fx
  :redirect
  (fn [url]
    (set! (.-href (.-location js/window)) url)))

(rf/reg-fx
  :navigate-to
  (fn [path]
    (.pushState (.-history js/window) nil "" path)
    (rf/dispatch [:route-changed path])))   ;; wire up to your router
