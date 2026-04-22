(ns monkey.ci.test.storage
  (:require [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [storage :as s]]
            [monkey.ci.storage.spec :as ss]))

(defn with-memory-store-fn [f]
  (f (s/make-memory-storage)))

(defmacro with-memory-store [s & body]
  `(with-memory-store-fn
     (fn [~s]
       ~@body)))

;;; Entity generators

(defn gen-entity [t]
  (gen/generate (spec/gen t)))

(defn gen-org []
  (gen-entity ::ss/org))

(def ^:deprecated gen-cust gen-org)

(defn gen-repo []
  (gen-entity ::ss/repo))

(defn gen-webhook []
  (gen-entity ::ss/webhook))

(defn gen-ssh-key []
  (gen-entity ::ss/ssh-key))

(defn gen-org-params []
  (gen-entity ::ss/org-params))

(def ^:deprecated gen-customer-params gen-org-params)

(defn gen-user []
  (gen-entity ::ss/user))

(defn gen-build []
  (-> (gen-entity ::ss/build)
      ;; TODO Put this in the spec itself
      (update-in [:script :jobs] (fn [jobs]
                                   (->> jobs
                                        (mc/map-kv-vals #(assoc %2 :id %1))
                                        (into {}))))))

(defn gen-job []
  (gen-entity ::ss/job))

(defn gen-join-request []
  (gen-entity ::ss/join-request))

(defn gen-email-registration []
  (gen-entity ::ss/email-registration))

(defn gen-email-confirmation []
  (gen-entity ::ss/email-confirmation))

(defn- update-amount [x]
  (update x :amount bigdec))

(defn gen-org-credit []
  (-> (gen-entity ::ss/org-credit)
      (update-amount)))

(def ^:deprecated gen-cust-credit gen-org-credit)

(defn gen-credit-subs []
  (-> (gen-entity ::ss/credit-subscription)
      (update-amount)))

(defn gen-credit-cons []
  (-> (gen-entity ::ss/credit-consumption)
      (update-amount)))

(defn gen-bb-webhook []
  (gen-entity ::ss/bb-webhook))

(defn gen-crypto []
  (gen-entity ::ss/crypto))

(defn gen-invoice []
  (gen-entity ::ss/invoice))

(defn gen-queued-task []
  (-> (gen-entity ::ss/queued-task)
      ;; Fixed task, to avoid brittle tests
      (assoc :task {:key :value})))

(defn gen-job-evt []
  (gen-entity ::ss/job-event))

(defn gen-user-token []
  (gen-entity ::ss/user-token))

(defn gen-org-token []
  (gen-entity ::ss/org-token))

(defn gen-mailing []
  (gen-entity ::ss/mailing))

(defn gen-sent-mailing []
  (gen-entity ::ss/sent-mailing))

(defn gen-user-settings []
  (gen-entity ::ss/user-setting))

(defn gen-org-invoicing []
  (gen-entity ::ss/org-invoicing))

(defn gen-org-plan []
  (gen-entity ::ss/org-plan))

(defn gen-build-sid []
  (repeatedly 3 cuid/random-cuid))
