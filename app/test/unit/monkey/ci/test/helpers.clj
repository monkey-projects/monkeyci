(ns monkey.ci.test.helpers
  "Helper functions for testing"
  (:require [aleph.netty :as an]
            [camel-snake-kebab.core :as csk]
            [cheshire.core :as json]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [storage :as s]
             [vault :as v]]
            [monkey.ci.web
             [auth :as auth]
             [common :as wc]]
            [ring.mock.request :as mock])
  (:import (org.apache.commons.io FileUtils)))

(defn ^java.io.File create-tmp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "tmp-" (random-uuid)))
    (.mkdirs)))

(defn with-tmp-dir-fn
  "Creates a temp dir and passes it to `f`.  Recursively deletes the temp dir afterwards."
  [f]
  (let [tmp (create-tmp-dir)]
    (try
      (f (.getAbsolutePath tmp))
      (finally
        (FileUtils/deleteDirectory tmp)))))

(defmacro with-tmp-dir [dir & body]
  `(with-tmp-dir-fn
     (fn [d#]
       (let [~dir d#]
         ~@body))))

(defn wait-until [f timeout]
  (let [end (+ (System/currentTimeMillis) timeout)]
    (loop [r (f)]
      (if r
        r
        (if (> (System/currentTimeMillis) end)
          :timeout
          (do
            (Thread/sleep 100)
            (recur (f))))))))

(defn try-take [ch & [timeout timeout-val]]
  (let [t (ca/timeout (or timeout 1000))
        [v c] (ca/alts!! [ch t])]
    (if (= t c) (or timeout-val :timeout) v)))

(defn with-memory-store-fn [f]
  (f (s/make-memory-storage)))

(defmacro with-memory-store [s & body]
  `(with-memory-store-fn
     (fn [~s]
       ~@body)))

(defn parse-json [s]
  (json/parse-string s csk/->kebab-case-keyword))

(defn reply->json
  "Takes the reply body and parses it from json"
  [rep]
  (some-> rep
          :body
          slurp
          parse-json))

(defn to-json
  "Converts object to json and converts keys to camelCase"
  [obj]
  (json/generate-string obj (comp csk/->camelCase name)))

(defn to-raw-json
  "Converts object to json without converting keys"
  [obj]
  (json/generate-string obj))

(defn json-request
  "Creates a Ring mock request with given object as json body"
  [method path body]
  (-> (mock/request method path)
      (mock/body (to-json body))
      (mock/header :content-type "application/json")))

(defn contains-subseq?
  "Predicate that checks if the `l` seq contains the `expected` subsequence."
  [l expected]
  (let [n (count expected)]
    (loop [t l]
      (if (= (take n t) expected)
        true
        (if (< (count t) n)
          false
          (recur (rest t)))))))

(defrecord FakeBlobStore [stored strict?]
  p/BlobStore  
  (save-blob [_ src dest md]
    (md/success-deferred (swap! stored assoc dest src)))

  (restore-blob [_ src dest]
    (if (or (not strict?)
            (= dest (get @stored src)))
      (md/success-deferred
       (do
         (swap! stored dissoc src)
         {:src src
          :dest dest
          :entries []}))
      (md/error-deferred (ex-info
                          (format "destination path was not as expected: %s, actual: %s" (get @stored src) dest)
                          @stored))))
  
  (get-blob-stream [_ src]
    (md/success-deferred
     (when (contains? @stored src)
       (io/input-stream (.getBytes "This is a test stream")))))

  (put-blob-stream [this src dest]
    (p/save-blob this src dest nil))

  (get-blob-info [_ _]
    nil))

(defn fake-blob-store
  ([stored]
   (->FakeBlobStore stored false))
  ([]
   (fake-blob-store (atom {}))))

(defn strict-fake-blob-store [stored]
  (->FakeBlobStore stored true))

(defn ->match-data [obj]
  {:reitit.core/match
   {:data obj}})

(defn ->req
  "Takes a runtime and creates a request object from it that can be passed to
   an api handler function."
  [rt]
  (->match-data
   {:monkey.ci.web.common/runtime (wc/->RuntimeWrapper rt)}))

(defn with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn with-query-param [r k v]
  (assoc-in r [:parameters :query k] v))

(defn with-path-params [r p]
  (update-in r [:parameters :path] merge p))

(defn with-body [r v]
  (assoc-in r [:parameters :body] v))

(defn with-identity [r id]
  (assoc r :identity id))

(defn ^:deprecated test-rt
  "Deprecated, use `runtime/test-runtime`"
  []
  {:storage (s/make-memory-storage)
   :jwk (auth/keypair->rt (auth/generate-keypair))})

(defn generate-private-key []
  (.getPrivate (auth/generate-keypair)))

(defn first-event-by-type [type events]
  (->> events
       (filter (comp (partial = type) :type))
       (first)))

(defrecord FakeServer [closed?]
  java.lang.AutoCloseable
  (close [_]
    (reset! closed? true))
  an/AlephServer
  (port [_]
    0))

(defn base64->
  "Converts from base64"
  [x]
  (when x
    (String.
     (.. (java.util.Base64/getDecoder)
         (decode x)))))

(defn parse-token-payload
  "Given an API JWT token, extracts and parses the payload.  This does
   not verify the signature."
  [token]
  (-> token
      (cs/split #"\.")
      (second)
      (base64->)
      (parse-json)))

;;; Entity generators

(defn gen-entity [t]
  (gen/generate (spec/gen t)))

(defn gen-org []
  (gen-entity :entity/org))

(def ^:deprecated gen-cust gen-org)

(defn gen-repo []
  (gen-entity :entity/repo))

(defn gen-webhook []
  (gen-entity :entity/webhook))

(defn gen-ssh-key []
  (gen-entity :entity/ssh-key))

(defn gen-org-params []
  (gen-entity :entity/org-params))

(def ^:deprecated gen-customer-params gen-org-params)

(defn gen-user []
  (gen-entity :entity/user))

(defn gen-build []
  (-> (gen-entity :entity/build)
      ;; TODO Put this in the spec itself
      (update-in [:script :jobs] (fn [jobs]
                                   (->> jobs
                                        (mc/map-kv-vals #(assoc %2 :id %1))
                                        (into {}))))))

(defn gen-job []
  (gen-entity :entity/job))

(defn gen-join-request []
  (gen-entity :entity/join-request))

(defn gen-email-registration []
  (gen-entity :entity/email-registration))

(defn- update-amount [x]
  (update x :amount bigdec))

(defn gen-org-credit []
  (-> (gen-entity :entity/org-credit)
      (update-amount)))

(def ^:deprecated gen-cust-credit gen-org-credit)

(defn gen-credit-subs []
  (-> (gen-entity :entity/credit-subscription)
      (update-amount)))

(defn gen-credit-cons []
  (-> (gen-entity :entity/credit-consumption)
      (update-amount)))

(defn gen-bb-webhook []
  (gen-entity :entity/bb-webhook))

(defn gen-crypto []
  (gen-entity :entity/crypto))

(defn gen-invoice []
  (gen-entity :entity/invoice))

(defn gen-queued-task []
  (-> (gen-entity :entity/queued-task)
      ;; Fixed task, to avoid brittle tests
      (assoc :task {:key :value})))

(defn gen-job-evt []
  (gen-entity :entity/job-event))

(defn gen-user-token []
  (gen-entity :entity/user-token))

(defn gen-org-token []
  (gen-entity :entity/org-token))

(defn gen-mailing []
  (gen-entity :entity/mailing))

(defn gen-sent-mailing []
  (gen-entity :entity/sent-mailing))

(defn gen-user-settings []
  (gen-entity :entity/user-setting))

(defn gen-build-sid []
  (repeatedly 3 cuid/random-cuid))

(defrecord DummyVault [enc-fn dec-fn]
  p/Vault
  (encrypt [_ _ v]
    (enc-fn v))

  (decrypt [_ _ v]
    (dec-fn v)))

(defn dummy-vault
  ([]
   (->DummyVault identity identity))
  ([enc-fn dec-fn]
   (->DummyVault enc-fn dec-fn)))

(def fake-vault dummy-vault)

(defmethod v/make-vault :noop [_]
  (fake-vault))

(defrecord FakeMailer [mailings]
  p/Mailer
  (send-mail [_ mail]
    (swap! mailings conj mail)
    {:type :fake
     :id (str (random-uuid))
     :mail mail}))

(defn fake-mailer []
  (->FakeMailer (atom [])))
