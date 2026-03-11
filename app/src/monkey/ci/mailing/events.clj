(ns monkey.ci.mailing.events
  "Event handlers for mailings.  E.g. to send out confirmation mails."
  (:require [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci
             [edn :as edn]
             [protocols :as p]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.mailing.template :as mt]))

(def get-config ::config)

(defn set-config [ctx c]
  (assoc ctx ::config c))

(def get-scheduled-mails ::scheduled-mails)

(defn set-scheduled-mails [ctx m]
  (assoc ctx ::scheduled-mails m))

(def get-mail-results ::mail-results)

(def get-mailer (comp :mailer get-config))

(defn set-mail-results [ctx r]
  (assoc ctx ::mail-results r))

(defn- apply-templates [email params]
  (-> email
      (update :html-body mt/apply-template params)
      (update :text-body mt/apply-template params)))

;;; Interceptors

(defn add-config [conf]
  {:name ::add-config
   :enter #(set-config % conf)})

(def result->scheduled
  {:name ::result->scheduled
   :leave (fn [ctx]
            (-> ctx
                (ec/set-result nil)
                (set-scheduled-mails (ec/result ctx))))})

(def send-scheduled-mails
  {:name ::send-scheduled
   :leave (fn [ctx]
            (->> (get-scheduled-mails ctx)
                 (map (partial p/send-mail (get-mailer ctx)))
                 (apply md/zip)
                 (deref)
                 (set-mail-results ctx)))})

;;; Handlers

(defn confirmation-created [ctx]
  (let [code (-> (:event ctx)
                 (select-keys [:id :code])
                 (pr-str)
                 (u/->base64))
        email (get-in ctx [:event :email])
        params {:code code
                :email email
                :EMAIL email
                :site-url (-> ctx (get-config) :site-url)}]
    (with-open [r (io/reader (io/resource "emails/confirmation.edn"))]
      (-> r
          (edn/edn->)
          (assoc :destinations [(get-in ctx [:event :email])])
          (apply-templates params)
          (vector)))))

(defn make-routes [conf]
  [[:email/confirmation-created
    [{:handler confirmation-created
      :interceptors [send-scheduled-mails
                     result->scheduled
                     (add-config conf)]}]]])
