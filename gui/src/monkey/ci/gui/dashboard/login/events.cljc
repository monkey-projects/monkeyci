(ns monkey.ci.gui.dashboard.login.events
  (:require [re-frame.core           :as rf]
            [day8.re-frame.http-fx]               ;; registers :http-xhrio effect
            [ajax.core               :refer [json-request-format
                                             json-response-format]]
            [monkey.ci.gui.dashboard.login.http :as http]
            [clojure.string          :as str]))

;; ── Interceptors ───────────────────────────────────────────────
;;
;; Trim whitespace from :email on every event that touches the db.
;; Add more interceptors here (e.g. schema validation, analytics).

(def trim-email
  (rf/->interceptor
    :id :trim-email
    :after (fn [ctx]
             (update-in ctx [:effects :db :email] #(some-> % str/trim)))))

;; ── Field updates ──────────────────────────────────────────────

(rf/reg-event-db
  :set-email
  [trim-email]
  (fn [db [_ val]]
    (-> db
        (assoc  :email val)
        (update :errors dissoc :email)
        (dissoc :error-banner))))

(rf/reg-event-db
  :set-password
  (fn [db [_ val]]
    (-> db
        (assoc  :password val)
        (update :errors dissoc :password)
        (dissoc :error-banner))))

(rf/reg-event-db
  :set-remember
  (fn [db [_ val]]
    (assoc db :remember val)))

;; ── Validation ─────────────────────────────────────────────────

(defn valid-email? [s]
  (boolean (re-matches #"^[^\s@]+@[^\s@]+\.[^\s@]+$" (str/trim (or s "")))))

(defn validate [{:keys [email password]}]
  (cond-> {}
    (not (valid-email? email))
    (assoc :email "Please enter a valid email address.")

    (< (count password) 8)
    (assoc :password "Password must be at least 8 characters.")))

;; ── Login submit ───────────────────────────────────────────────
;;
;; :submit-login validates locally first, then fires :http-xhrio.
;; On success the server returns:
;;   {:token "...", :workspace "...", :user {...}}
;; On failure it returns a 401 with:
;;   {:error "invalid_credentials", :message "..."}

(rf/reg-event-fx
  :submit-login
  (fn [{:keys [db]} _]
    (let [errors (validate db)]
      (if (seq errors)
        ;; Client-side validation failed — no HTTP call
        {:db (assoc db :errors errors)}

        ;; Validation passed — fire the real request
        {:db         (assoc db :loading?     true
                               :errors       {}
                               :error-banner nil)
         :http-xhrio {:method          :post
                      :uri             (http/endpoint "/auth/login")
                      :headers         http/default-headers
                      :params          {:email    (:email db)
                                        :password (:password db)
                                        :remember (:remember db)}
                      :format          (json-request-format)
                      :response-format (json-response-format {:keywords? true})
                      :on-success      [:login-success]
                      :on-failure      [:login-failure]
                      :timeout         10000}}))))

;; ── Login success ──────────────────────────────────────────────
;;
;; Server responded 2xx.  Store the token, then navigate to the
;; dashboard.  In a real app you'd use reitit or accountant here.

(rf/reg-event-fx
  :login-success
  (fn [{:keys [db]} [_ {:keys [token workspace user]}]]
    {:db       (assoc db :loading? false
                         :token    token
                         :user     user)
     :fx       [[:store-token   token]             ;; custom effect (below)
                [:navigate-to   (str "/" workspace "/overview")]]}))

;; ── Login failure ──────────────────────────────────────────────
;;
;; http-fx delivers the full XHR response map as the second
;; element: {:status 401, :response {:error "...", :message "..."}}

(rf/reg-event-fx
  :login-failure
  (fn [{:keys [db]} [_ {:keys [status response]}]]
    (let [error-key (case status
                      401 :invalid-credentials
                      403 :account-locked
                      422 :validation-error
                      429 :rate-limited
                      503 :service-unavailable
                          :unknown-error)

          ;; Surface server-provided message when available
          server-msg (:message response)]

      {:db (assoc db :loading?     false
                     :error-banner error-key
                     :server-msg   server-msg
                     :errors       (when (= status 401)
                                     {:email true :password true}))})))

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
    {:db         (assoc db :oauth-loading provider
                           :error-banner  nil)
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
     :fx     [[:store-token  token]
              [:navigate-to  (str "/" workspace "/overview")]]}))

;; ── Password reset ─────────────────────────────────────────────

(rf/reg-event-fx
  :request-password-reset
  (fn [{:keys [db]} _]
    (if-not (valid-email? (:email db))
      {:db (assoc-in db [:errors :email] "Enter your email address first.")}
      {:db         (assoc db :reset-loading? true)
       :http-xhrio {:method          :post
                    :uri             (http/endpoint "/auth/password-reset")
                    :headers         http/default-headers
                    :params          {:email (:email db)}
                    :format          (json-request-format)
                    :response-format (json-response-format {:keywords? true})
                    :on-success      [:reset-email-sent]
                    :on-failure      [:reset-email-failed]
                    :timeout         8000}})))

(rf/reg-event-db
  :reset-email-sent
  (fn [db _]
    (assoc db :reset-loading?  false
              :reset-sent?     true
              :error-banner    nil)))

(rf/reg-event-db
  :reset-email-failed
  (fn [db [_ {:keys [status]}]]
    (assoc db :reset-loading? false
              :error-banner   (if (= status 429)
                                :rate-limited
                                :reset-failed))))

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
     :fx [[:store-token token]]}))

(rf/reg-event-fx
  :token-expired
  (fn [{:keys [db]} _]
    {:db (assoc db :loading? false :token nil)
     :fx [[:clear-token]
          [:navigate-to "/login"]]}))

;; ── Custom effects ─────────────────────────────────────────────
;;
;; These keep side-effectful operations (localStorage, navigation)
;; out of event handlers and therefore easily testable.

(rf/reg-fx
  :store-token
  (fn [token]
    (.setItem (.-localStorage js/window) "mci_token" token)))

(rf/reg-fx
  :clear-token
  (fn [_]
    (.removeItem (.-localStorage js/window) "mci_token")))

(rf/reg-fx
  :redirect
  (fn [url]
    (set! (.-href (.-location js/window)) url)))

(rf/reg-fx
  :navigate-to
  (fn [path]
    (.pushState (.-history js/window) nil "" path)
    (rf/dispatch [:route-changed path])))   ;; wire up to your router
