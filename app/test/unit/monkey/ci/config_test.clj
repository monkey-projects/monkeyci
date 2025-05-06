(ns monkey.ci.config-test
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as cs]
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.config :as sut]
   [monkey.ci.logging]
   [monkey.ci.spec :as spec]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.web.github]))

(defn- with-home-config [config body]
  (h/with-tmp-dir dir
    (let [f (-> (io/file dir "home-config.edn")
                (.getCanonicalPath))]
      (binding [sut/*home-config-file* f]
        (is (nil? (spit f (pr-str config))))
        (body)))))

(deftest default-app-config
  (testing "default config is valid config according to spec"
    (is (s/valid? ::spec/app-config sut/default-app-config)
        (s/explain-str ::spec/app-config sut/default-app-config))))

(deftest app-config
  (testing "provides default values"
    (is (= 3000 (-> (sut/app-config {} {})
                    :http
                    :port))))

  (testing "includes args"
    (is (= {::test-arg :test-val}
           (-> (sut/app-config {} {::test-arg :test-val})
               :args))))

  (testing "takes port from args"
    (is (= 1234 (-> (sut/app-config {} {:port 1234})
                    :http
                    :port))))

  (testing "sets `dev-mode` from args"
    (is (true? (->> {:dev-mode true}
                    (sut/app-config {})
                    :dev-mode))))

  (testing "matches app config spec"
    (with-redefs [sut/load-config-file (constantly nil)]
      (let [c (sut/app-config {} {})]
        (is (s/valid? ::spec/app-config c)
            (s/explain-str ::spec/app-config c)))))

  (testing "loads config from `config-file` path"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test-config.edn")]
        (is (nil? (spit f (pr-str {:work-dir "some-work-dir"}))))
        (let [c (sut/app-config {} {:config-file [(.getCanonicalPath f)]})]
          (is (cs/ends-with? (:work-dir c) "some-work-dir"))))))

  (testing "can specify multiple config files"
    (h/with-tmp-dir dir
      (let [[a b :as f] (->> ["first" "second"]
                             (map (partial format "%s-config.edn"))
                             (map (partial io/file dir)))]
        (is (nil? (spit a (pr-str {:work-dir "first-work-dir"}))))
        (is (nil? (spit b (pr-str {:work-dir "second-work-dir"}))))
        (let [c (sut/app-config {} {:config-file (mapv (memfn getCanonicalPath) f)})]
          (is (cs/ends-with? (:work-dir c) "second-work-dir"))))))

  (testing "loads global config file"
    (h/with-tmp-dir dir
      (let [f (-> (io/file dir "test-config.edn")
                  (.getCanonicalPath))]
        (binding [sut/*global-config-file* f]
          (is (nil? (spit f (pr-str {:work-dir "some-work-dir"}))))
          (let [c (sut/app-config {} {})]
            (is (cs/ends-with? (:work-dir c) "some-work-dir")))))))

  (testing "loads home config file"
    (with-home-config
      {:work-dir "some-work-dir"}
      #(let [c (sut/app-config {} {})]
         (is (cs/ends-with? (:work-dir c) "some-work-dir")))))

  (testing "global `work-dir`"
    (testing "uses current as default"
      (is (some? (-> (sut/app-config {} {})
                     :work-dir)))))

  (testing "ignores log dir from work dir when type is not `file`"
    (is (nil? (-> (sut/app-config {:monkeyci-work-dir "test-dir"} {:logging {:type :inherit}})
                  :logging
                  :dir))))

  (testing "takes account settings from args"
    (is (= {:org-id "test-customer"
            :repo-id "arg-repo"}
           (-> (sut/app-config {}
                               {:org-id "test-customer"
                                :repo-id "arg-repo"})
               :account
               (select-keys [:org-id :repo-id])))))

  (testing "uses `server` arg as account url"
    (is (= "http://test"
           (-> (sut/app-config {} {:server "http://test"})
               :account
               :url))))

  (testing "oci"
    (testing "keeps credentials from config file"
      (with-home-config
        {:logging
         {:type :oci
          :credentials
          {:key-fingerprint "conf-fingerprint"}}}
        #(is (= "conf-fingerprint" (->> (sut/app-config {} {})
                                        :logging
                                        :credentials
                                        :key-fingerprint)))))))

(deftest load-config-file
  (testing "`nil` if file does not exist"
    (is (nil? (sut/load-config-file "nonexisting.json"))))

  (testing "loads `.edn` files"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (spit f (pr-str {:key "value"}))
        (is (= {:key "value"} (sut/load-config-file f))))))

  (testing "can load private key from file"
    (h/with-tmp-dir dir
      (let [pk (h/generate-private-key)
            f (io/file dir "pk.edn")]
        (spit f (pr-str {:key pk}))
        (is (= pk (:key (sut/load-config-file f))))))))

(deftest home-config-file
  (testing "is by default in the user home dir"
    (is (= (str (System/getProperty "user.home") "/.monkeyci/config.edn")
           sut/*home-config-file*))))

