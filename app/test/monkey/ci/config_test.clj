(ns monkey.ci.config-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as cs]
            [monkey.ci
             [config :as sut]
             [logging]
             [spec :as spec]]
            [monkey.ci.web.github]
            [monkey.ci.helpers :as h]))

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

(deftest group-keys
  (testing "groups according to prefix, removes existing"
    (is (= {:test {:key "value"}}
           (sut/group-keys {:test-key "value"} :test))))

  (testing "leaves unchanged if no matches"
    (is (= {:other "value"}
           (sut/group-keys {:other "value"} :test)))))

(deftest group-and-merge
  (testing "merges existing with grouped"
    (is (= {:test
            {:key "value"
             :other-key "other-value"}}
           (sut/group-and-merge
            {:test {:key "value"}
             :test-other-key "other-value"}
            :test))))

  (testing "retains submaps"
    (is (= {:test {:credentials {:username "test-user"}}}
           (sut/group-and-merge
            {:test {:credentials {:username "test-user"}}}
            :test))))

  (testing "leaves unchanged when no matches"
    (is (= {:key "value"}
           (sut/group-and-merge {:key "value"} :test)))))

(deftest app-config
  (testing "provides default values"
    (is (= 3000 (-> (sut/app-config {} {})
                    :http
                    :port))))

  (testing "takes port from args"
    (is (= 1234 (-> (sut/app-config {} {:port 1234})
                    :http
                    :port))))

  (testing "takes port from env"
    (is (= 1234
           (-> (sut/app-config {:monkeyci-http-port 1234} {})
               :http
               :port))))

  (testing "takes github config from env"
    (is (= "test-secret"
           (-> {:monkeyci-github-secret "test-secret"}
               (sut/app-config {})
               :github
               :secret))))

  (testing "takes storage config from env"
    (is (= "test-dir"
           (-> {:monkeyci-storage-dir "test-dir"}
               (sut/app-config {})
               :storage
               :dir))))

  (testing "takes cache config from env"
    (is (= "test-dir"
           (-> {:monkeyci-cache-dir "test-dir"}
               (sut/app-config {})
               :cache
               :dir))))

  (testing "takes artifact config from env"
    (is (= "test-dir"
           (-> {:monkeyci-artifacts-dir "test-dir"}
               (sut/app-config {})
               :artifacts
               :dir))))

  (testing "takes sidecar config from env"
    (is (= "test-file"
           (-> {:monkeyci-sidecar-log-config "test-file"}
               (sut/app-config {})
               :sidecar
               :log-config))))

  (testing "sets runner type"
    (is (= :test-type (-> {:monkeyci-runner-type "test-type"}
                          (sut/app-config {})
                          :runner
                          :type))))

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
                     :work-dir))))

    (testing "takes work dir from env"
      (is (cs/ends-with? (-> (sut/app-config {:monkeyci-work-dir "test-dir"} {})
                             :work-dir)
                         "test-dir")))

    (testing "takes work dir from arg over env"
      (is (cs/ends-with? (-> (sut/app-config {:monkeyci-work-dir "test-dir"}
                                             {:workdir "arg-dir"})
                             :work-dir)
                         "arg-dir"))))

  (testing "calculates checkout base dir from work dir"
    (is (cs/includes? (-> (sut/app-config {:monkeyci-work-dir "test-dir"} {})
                          :checkout-base-dir)
                      "test-dir")))

  (testing "calculates log dir from work dir when type is `file`"
    (is (cs/includes? (-> (sut/app-config {:monkeyci-work-dir "test-dir"
                                           :monkeyci-logging-type "file"}
                                          {})
                          :logging
                          :dir)
                      "test-dir")))

  (testing "ignores log dir from work dir when type is not `file`"
    (is (nil? (-> (sut/app-config {:monkeyci-work-dir "test-dir"} {:logging {:type :inherit}})
                  :logging
                  :dir))))
  
  (testing "includes account"
    (is (= {:customer-id "test-customer"}
           (-> {:monkeyci-account-customer-id "test-customer"}
               (sut/app-config {})
               :account
               (select-keys [:customer-id])))))

  (testing "takes account settings from args"
    (is (= {:customer-id "test-customer"
            :repo-id "arg-repo"}
           (-> (sut/app-config {:monkeyci-account-customer-id "test-customer"}
                               {:repo-id "arg-repo"})
               :account
               (select-keys [:customer-id :repo-id])))))

  (testing "uses `server` arg as account url"
    (is (= "http://test"
           (-> (sut/app-config {} {:server "http://test"})
               :account
               :url))))

  (testing "oci"
    (testing "provides credentials from env"
      (is (= "env-fingerprint"
             (-> {:monkeyci-logging-credentials-key-fingerprint "env-fingerprint"
                  :monkeyci-logging-type "oci"}
                 (sut/app-config {})
                 :logging
                 :credentials
                 :key-fingerprint))))
    
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

(deftest config->env
  (testing "empty for empty input"
    (is (empty? (sut/config->env {}))))

  (testing "prefixes config entries with `monkeyci-`"
    (is (= {:monkeyci-key "value"}
           (sut/config->env {:key "value"}))))

  (testing "flattens nested config maps"
    (is (= {:monkeyci-http-port "8080"}
           (sut/config->env {:http {:port 8080}})))))

(deftest load-config-file
  (testing "`nil` if file does not exist"
    (is (nil? (sut/load-config-file "nonexisting.json"))))

  (testing "loads `.edn` files"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (spit f (pr-str {:key "value"}))
        (is (= {:key "value"} (sut/load-config-file f))))))

  (testing "loads `.json` files"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.json")]
        (spit f "{\"key\":\"value\"}")
        (is (= {:key "value"} (sut/load-config-file f))))))

  (testing "converts to kebab-case for `.edn`"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (spit f (pr-str {:testKey "value"}))
        (is (= {:test-key "value"} (sut/load-config-file f))))))

  (testing "converts to kebab-case for `.json`"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.json")]
        (spit f "{\"testKey\":\"value\"}")
        (is (= {:test-key "value"} (sut/load-config-file f)))))))

(deftest home-config-file
  (testing "is by default in the user home dir"
    (is (= (str (System/getenv "HOME") "/.monkeyci/config.edn")
           sut/*home-config-file*))))

(deftest normalize-config
  (testing "drops env and default keys"
    (let [n (sut/normalize-config {} {} {})]
      (is (not (contains? n :env)))
      (is (not (contains? n :default)))))

  
  
  (testing "github"
    (testing "adds config from env"
      (is (= {:client-id "test-id"
              :client-secret "test-secret"}
             (-> (sut/normalize-config
                  {:github {:client-id "orig-id"}}
                  {:github-client-id "test-id"
                   :github-client-secret "test-secret"}
                  {})
                 :github))))

    (testing "adds config if not specified in env"
      (is (= {:client-id "test-id"
              :client-secret "test-secret"}
             (-> (sut/normalize-config
                  {:github {:client-id "test-id"
                            :client-secret "test-secret"}}
                  {}
                  {})
                 :github)))))

  (testing "http config"
    (is (= {:port 3000}
           (-> (sut/normalize-config
                {}
                {:http-port 3000}
                {})
               :http))))

  (testing "merges global oci config in type specific"
    (is (= {:type :oci
            :bucket-name "test-bucket"
            :region "test-region"}
           (-> (sut/normalize-config
                {:oci
                 {:region "test-region"}
                 :storage
                 {:type :oci
                  :bucket-name "test-bucket"}}
                {}
                {})
               :storage))))

  (testing "merges global oci config in type specific from env"
    (is (= {:type :oci
            :bucket-name "test-bucket"
            :region "test-region"}
           (-> (sut/normalize-config
                {}
                {:oci-region "test-region"
                 :storage-type "oci"
                 :storage-bucket-name "test-bucket"}
                {})
               :storage))))

  (testing "adds credentials from global oci config"
    (is (= {:user "test-user"}
           (-> (sut/normalize-config
                {:oci
                 {:credentials {:user "test-user"}}
                 :storage
                 {:type :oci
                  :bucket-name "test-storage-bucket"}}
                {} {})
               :storage
               :credentials))))

  (testing "adds input config to result"
    (let [in {:key "value"}]
      (is (= in (-> (sut/normalize-config in {} {})
                    :original))))))
