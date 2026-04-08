(ns monkey.ci.gui.dashboard.login.events
  (:require [re-frame.core :as rf]
            [monkey.ci.gui.dashboard.login.db :as db]
            [monkey.ci.gui.local-storage :as l]
            ;; Reusing the original login events for this
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.login.events :as levt]
            [monkey.ci.gui.routing :as r]))

(def provider-configs
  {:github
   {:oauth-url "https://github.com/login/oauth/authorize"
    :config-load :login/load-github-config
    :extra-params {"response_type" "code"}
    :success-evt :login/github-code-received}})

(rf/reg-event-fx
 ::login-and-redirect
 (fn [{:keys [db]} _]
   (let [cr (r/current db)
         next-route (:path cr)]
     {:local-storage [levt/storage-redir-id
                      ;; Ignore public routes to avoid redirecting to the callback pages
                      (when-not (r/public? cr)
                        {:redirect-to next-route})]
      :dispatch [:route/goto :page/login]})))

(rf/reg-event-fx
 ::load-config
 (fn [_ [_ provider]]
   (when-let [e (get-in provider-configs [provider :config-load])]
     {:dispatch [e]})))

(defn oauth-login [{:keys [db]} [_ provider]]
  (let [c (get provider-configs provider)
        callback-url (str (r/origin) (r/path-for :page/oauth-callback {:provider (name provider)}))]
    {::redirect (str (:oauth-url c) "?" (ldb/build-auth-params (db/get-client-id db provider)
                                                               callback-url
                                                               (:extra-params c)))}))
(rf/reg-event-fx
 ::oauth-login
 oauth-login)

;; Called by the backend redirect after successful OAuth
(rf/reg-event-fx
  ::oauth-callback-success
  (fn [{:keys [db]} [_ provider code]]
    {:dispatch [(get-in provider-configs [(keyword provider) :success-evt]) code]}))

;; ── Custom effects ─────────────────────────────────────────────
;;
;; These keep side-effectful operations (localStorage, navigation)
;; out of event handlers and therefore easily testable.

(rf/reg-fx
  ::redirect
  (fn [url]
    (set! (.-href (.-location js/window)) url)))
