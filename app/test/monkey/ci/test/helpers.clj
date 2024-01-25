(ns monkey.ci.test.helpers
  "Helper functions for testing"
  (:require [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.core.async :as ca]
            [cheshire.core :as json]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [events :as e]
             [storage :as s]]
            [monkey.ci.web.auth :as auth]
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

(defn with-bus [f]
  (let [bus (e/make-bus)]
    (try
      (f bus)
      (finally
        (e/close-bus bus)))))

(defn with-memory-store-fn [f]
  (f (s/make-memory-storage)))

(defmacro with-memory-store [s & body]
  `(with-memory-store-fn
     (fn [~s]
       ~@body)))

(defn parse-json [s]
  (json/parse-string s csk/->kebab-case-keyword))

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

(defrecord FakeBlobStore [stored]
  blob/BlobStore
  (save [_ src dest]
    (md/success-deferred (swap! stored assoc src dest)))
  (restore [_ src dest]
    (md/success-deferred (swap! stored dissoc src))))

(defn ->req
  "Takes a context and creates a request object from it that can be passed to
   an api handler function."
  [ctx]
  {:reitit.core/match
   {:data
    {:monkey.ci.web.common/context ctx}}})

(defn with-path-param [r k v]
  (assoc-in r [:parameters :path k] v))

(defn with-path-params [r p]
  (update-in r [:parameters :path] merge p))

(defn with-body [r v]
  (assoc-in r [:parameters :body] v))

(defn test-ctx []
  {:storage (s/make-memory-storage)
   :jwk (auth/keypair->ctx (auth/generate-keypair))})
