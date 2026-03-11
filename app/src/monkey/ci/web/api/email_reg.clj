(ns monkey.ci.web.api.email-reg
  "Email registrations for mailings"
  (:require [monkey.ci
             [storage :as st]
             [time :as t]]
            [monkey.ci.web
             [common :as c]
             [response :as wr]]
            [ring.util.response :as rur]))

(c/make-entity-endpoints "email-registration"
                         {:get-id (c/id-getter :email-registration-id)
                          :getter st/find-email-registration
                          :deleter st/delete-email-registration})

(defn new-confirmation-code []
  (str (random-uuid)))

(defn create-email-registration
  "Custom creation endpoint that ensures emails are not registered twice."
  [req]
  (let [st (c/req->storage req)
        {:keys [email] :as body} (-> (c/body req)
                                     (assoc :id (st/new-id)
                                            :creation-time (t/now)
                                            :confirmed false))]
    (if-let [existing (st/find-email-registration-by-email st email)]
      (rur/response existing)
      (st/with-transaction st trx
        (when (st/save-email-registration trx body)
          (let [conf {:email-reg-id (:id body)
                      :id (st/new-id)
                      :creation-time (:creation-time body)
                      :code (new-confirmation-code)}]
            (st/save-email-confirmation trx conf)
            (-> (rur/created (:id body) body)
                ;; Also send events, so confirmation email can be sent
                (wr/add-events [(-> body
                                    (select-keys [:id :email :creation-time])
                                    (assoc :type :email/registration-created))
                                (-> conf
                                    (select-keys [:code :creation-time])
                                    (merge (select-keys body [:id :email]))
                                    (assoc :type :email/confirmation-created))]))))))))

(defn- unregister-by-id [req]
  (st/delete-email-registration (c/req->storage req)
                                (get-in req [:parameters :query :id])))

(defn- delete-reg
  "Deletes email registration for given email"
  [st email]
  ;; TODO Just delete them all, no need to lookup
  (when-let [m (st/find-email-registration-by-email st email)]
    (st/delete-email-registration st (:id m))))

(defn- unregister-settings [st u]
  (let [s (st/find-user-settings st (:id u))]
    (st/save-user-settings st (assoc s
                                     :user-id (:id u)
                                     :receive-mailing false))))

(defn- unregister-users
  "Updates all user settings with given email to no longer receive mailings"
  [st email]
  (->> (st/find-users-by-email st email)
       (map (partial unregister-settings st))
       (not-empty)
       (some?)))

(defn- unregister-by-email [req]  
  (let [st (c/req->storage req)
        email (get-in req [:parameters :query :email])]
    (->> [(delete-reg st email)
          (unregister-users st email)]
         (some true?))))

(defn- unregister-user [req]
  (let [st (c/req->storage req)]
    (when-let [u (st/find-user st (get-in req [:parameters :query :user-id]))]
      (unregister-settings st u))))

(defn unregister-email
  "Multipurpose unregistration handler, meant to allow people to easily unsubscribe
   from the mailing list.  It accepts a subscription id, an email or a user id.  If
   it's an email subscription, the record is deleted.  If it's a user id or email,
   the user settings are updated to no longer receive mailings."
  [req]
  (letfn [(has-param? [p req]
            (some? (get-in req [:parameters :query p])))]
    (if (condp has-param? req
          :id (unregister-by-id req)
          :email (unregister-by-email req)
          :user-id (unregister-user req)
          false)
      (rur/status 200)
      (rur/status 204))))

(defn confirm-email [req]
  (let [b (c/body req)
        st (c/req->storage req)]
    (if-let [reg (st/find-email-registration st (:id b))]
      (if (:confirmed reg)
        (rur/status 204)
        (if-let [c (->> (st/list-email-confirmations st (:id reg))
                        (filter (comp (partial = (:code b)) :code))
                        (first))]
          (do
            (st/save-email-registration st (assoc reg :confirmed true))
            ;; TODO Delete any other confirmations as well
            (st/delete-email-confirmation st (:id c))
            (rur/response reg))
          ;; TODO Also fail if confirmation has expired
          (c/error-response "Invalid confirmation code")))
      (rur/not-found nil))))
