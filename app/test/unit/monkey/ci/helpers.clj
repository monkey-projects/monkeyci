(ns monkey.ci.helpers
  "Helper functions for testing"
  (:require [aleph.netty :as an]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.core.async :as ca]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [cheshire.core :as json]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [protocols :as p]
             [storage :as s]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web
             [common :as wc]
             [auth :as auth]]
            [ring.mock.request :as mock])
  (:import org.apache.commons.io.FileUtils))

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
  (save-blob [_ src dest]
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
    (p/save-blob this src dest)))

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

(defrecord FakeEvents [recv]
  p/EventPoster
  (post-events [this evt]
    (swap! recv (comp vec concat) (u/->seq evt))))

(defn fake-events
  "Set up fake events implementation.  It returns an event poster that can be
   queried for received events."
  []
  (->FakeEvents (atom [])))

(defn received-events [fake]
  @(:recv fake))

(defmethod ec/make-events :fake [_]
  (fake-events))

(defrecord FakeEventReceiver [listeners]
  p/EventReceiver
  (add-listener [this ef h]
    (swap! listeners update ef (fnil conj []) h))

  (remove-listener [this ef h]
    (swap! listeners update ef (partial remove (partial = h)))))

(defn fake-events-receiver []
  (->FakeEventReceiver (atom {})))

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

(defn gen-cust []
  (gen-entity :entity/customer))

(defn gen-repo []
  (gen-entity :entity/repo))

(defn gen-webhook []
  (gen-entity :entity/webhook))

(defn gen-ssh-key []
  (gen-entity :entity/ssh-key))

(defn gen-customer-params []
  (gen-entity :entity/customer-params))

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
