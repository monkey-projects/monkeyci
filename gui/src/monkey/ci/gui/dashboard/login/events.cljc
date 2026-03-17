(ns monkey.ci.gui.dashboard.login.events
  (:require [re-frame.core :as rf]
            [day8.re-frame.http-fx]
            [ajax.core :refer [json-request-format
                               json-response-format]]
            [monkey.ci.gui.dashboard.login.http :as http]
            [monkey.ci.gui.local-storage :as l]
            [clojure.string :as str]))

(def token-id "mci_token")

;; ── OAuth ──────────────────────────────────────────────────────
;;
;; Step 1: ask our backend for a provider-specific redirect URL.
;; Step 2: redirect the browser to the OAuth provider.
;; Step 3: after the OAuth flow the provider calls our callback,
;;         the backend issues a token, and redirects back to the
;;         app — which fires :oauth-callback-success.

(rf/reg-event-fx
 :oauth-login
 (fn [{:keys [db]} [_ provider]]
   {:db (-> db
            (assoc :oauth-loading provider)
            (dissoc :error-banner))
    :http-xhrio {:method          :get
                 :uri             (http/endpoint
                                   (str "/auth/oauth/"
                                        (name provider)
                                        "/authorize"))
                 :headers         http/default-headers
                 :format          (json-request-format)
                 :response-format (json-response-format {:keywords? true})
                 :on-success      [:oauth-redirect]
                 :on-failure      [:oauth-failure provider]
                 :timeout         8000}}))

(rf/reg-event-fx
  :oauth-redirect
  (fn [{:keys [db]} [_ {:keys [redirect-url]}]]
    ;; Hand off to browser — the :redirect effect is defined below
    {:db       (assoc db :oauth-loading nil)
     :redirect redirect-url}))

(rf/reg-event-fx
  :oauth-failure
  (fn [{:keys [db]} [_ provider _response]]
    {:db (assoc db :oauth-loading nil
                   :error-banner  :oauth-error
                   :oauth-provider provider)}))

;; Called by the backend redirect after successful OAuth
(rf/reg-event-fx
  :oauth-callback-success
  (fn [{:keys [db]} [_ {:keys [token workspace user]}]]
    {:db     (assoc db :token token :user user)
     :fx     [[:local-storage [token-id token]]
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
