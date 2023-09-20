(ns monkey.ci.test.helpers
  "Helper functions for testing"
  (:require [clojure.java.io :as io]
            [clojure.core.async :as ca]
            [monkey.ci.events :as e])
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

(defn try-take [ch timeout timeout-val]
  (let [t (ca/timeout timeout)
        [v c] (ca/alts!! [ch t])]
    (if (= t c) timeout-val v)))

(defn with-bus [f]
  (let [bus (e/make-bus)]
    (try
      (f bus)
      (finally
        (e/close-bus bus)))))
