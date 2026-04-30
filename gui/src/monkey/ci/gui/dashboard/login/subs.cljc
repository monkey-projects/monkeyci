(ns monkey.ci.gui.dashboard.login.subs
  (:require [re-frame.core :as rf]))

;; Raw db slices
(rf/reg-sub ::email          #(:email %))
(rf/reg-sub ::password       #(:password %))
(rf/reg-sub ::remember       #(:remember %))
(rf/reg-sub ::loading?       #(:loading? %))
(rf/reg-sub ::errors         #(:errors %))
(rf/reg-sub ::error-banner   #(:error-banner %))
(rf/reg-sub ::oauth-loading  #(:oauth-loading %))
(rf/reg-sub ::reset-sent?    #(:reset-sent? %))
(rf/reg-sub ::reset-loading? #(:reset-loading? %))
(rf/reg-sub ::server-msg     #(:server-msg %))

;; Derived
(rf/reg-sub
  ::any-loading?
  :<- [::loading?]
  :<- [::oauth-loading]
  :<- [::reset-loading?]
  (fn [[loading? oauth reset] _]
    (or loading? (some? oauth) reset)))

(rf/reg-sub
  ::error-message
  :<- [::error-banner]
  :<- [::server-msg]
  (fn [[banner server-msg] _]
    (or server-msg
        (case banner
          :invalid-credentials "Invalid email or password. Please try again."
          :account-locked      "This account is locked. Contact support."
          :rate-limited        "Too many attempts. Please wait a moment."
          :oauth-error         "OAuth sign-in failed. Please try again."
          :service-unavailable "Service is temporarily unavailable."
          :reset-failed        "Could not send reset email. Try again."
          :validation-error    "Please check your input and try again."
          :unknown-error       "An unexpected error occurred."
          nil))))
