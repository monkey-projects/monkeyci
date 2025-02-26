(ns monkey.ci.git-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [buddy.core.codecs :as bcc]
            [clj-jgit.porcelain :as git]
            [monkey.ci.git :as sut]
            [monkey.ci.helpers :as h]
            [monkey.ci.web.auth :as auth]
            [monkey.oci.common.utils :as ocu]))

(deftest clone
  (testing "invokes `git-clone` without checking out"
    (with-redefs [git/git-clone (fn [& args]
                                  args)]
      (is (= ["http://url" :branch "master" :dir "tmp"]
             (sut/clone {:url "http://url"
                         :branch "master"
                         :dir "tmp"})))))

  (testing "can check out tags"
    (with-redefs [git/git-clone (fn [& args]
                                  args)]
      (is (= ["http://url" :branch "test-tag" :dir "tmp"]
             (sut/clone {:url "http://url"
                         :tag "test-tag"
                         :dir "tmp"}))))))

(deftest checkout
  (testing "invokes `git-checkout`"
    (with-redefs [git/git-checkout (fn [& args]
                                     args)]
      (is (= [:test-repo {:name "test-id"}]
             (sut/checkout :test-repo "test-id"))))))

(deftest clone+checkout
  (testing "clones, then checks out commit id"
    (let [checkout-args (atom nil)]
      (with-redefs [sut/clone (constantly :test-repo)
                    sut/checkout (fn [repo id]
                                   (reset! checkout-args {:repo repo
                                                          :commit-id id}))]
        (is (= :test-repo
               (sut/clone+checkout {:url "http://test-url"
                                    :branch "main"
                                    :commit-id "test-id"
                                    :dir "test-dir"})))
        (is (= "test-id" (:commit-id @checkout-args))))))

  (testing "when no commit id, just clones"
    (let [checkout-ids (atom nil)]
      (with-redefs [sut/clone (constantly :test-repo)
                    sut/checkout (fn [_ id]
                                   (reset! checkout-ids id))]
        (is (= :test-repo
               (sut/clone+checkout {:url "http://test-url"
                                    :ref "refs/heads/main"
                                    :dir "test-dir"})))
        (is (empty? @checkout-ids))))))

(defn- keypair-base64
  "Generates a new RSA keypair and returns the public and private keys as base64
   encoded strings."
  []
  (let [r (->> (auth/generate-keypair)
               ((juxt (memfn getPrivate) (memfn getPublic)))
               (map (comp #(String. %) bcc/bytes->b64 (memfn getEncoded)))
               (zipmap [:private-key :public-key]))]
    (-> r
        (update :private-key #(str "----BEGIN RSA PRIVATE KEY-----\n" % "\n-----END RSA PRIVATE KEY-----"))
        (update :public-key #(str "ssh-rsa " %)))))

(deftest prepare-ssh-keys
  (testing "empty if no keys configured"
    (is (empty? (sut/prepare-ssh-keys {}))))

  (testing "when keys dir provided, but no ssh keys, lists private keys"
    (h/with-tmp-dir dir
      (let [ssh-dir (str (-> (fs/path dir "ssh-keys")
                             (fs/create-dirs)))
            {pub :public-key priv :private-key :as kp} (keypair-base64)]
        (is (nil? (spit (str (fs/path ssh-dir "key-0")) priv)))
        (is (nil? (spit (str (fs/path ssh-dir "key-0.pub")) pub)))
        (is (= {:key-dir ssh-dir
                :name ["key-0"]}
               (sut/prepare-ssh-keys {:ssh-keys-dir ssh-dir}))))))

  (testing "writes keys to configured dir with public key"
    (h/with-tmp-dir dir
      (let [ssh-dir (str (fs/path dir "ssh-keys"))
            keys (keypair-base64)]
        (is (some? ssh-dir))
        (is (= {:key-dir ssh-dir
                :name ["key-0"]}
               (sut/prepare-ssh-keys {:ssh-keys [keys]
                                      :ssh-keys-dir ssh-dir})))
        (is (fs/exists? (fs/path ssh-dir "key-0")))
        (is (fs/exists? (fs/path ssh-dir "key-0.pub")))))))

(deftest copy-with-ignore
  (h/with-tmp-dir dir
    (testing "when no .gitignore, copies src to dest"
      (let [src (fs/path dir (str (random-uuid)))
            dest (fs/path dir (str (random-uuid)))]
        (is (some? (fs/create-dirs src)))
        (is (nil? (spit (fs/file (fs/path src "test.txt")) "test file")))
        (is (= dest (sut/copy-with-ignore src dest)))
        (is (fs/exists? (fs/path dest "test.txt")))))

    (testing "applies gitignore in each directory"
      (let [src (fs/path dir (str (random-uuid)))
            sub (fs/path src "sub")
            dest (fs/path dir (str (random-uuid)))
            files {".gitignore" "*.txt\nsub/other"
                   "sub/.gitignore" "*.edn"
                   "ignored.txt" "ignored"
                   "included.edn" "not ignored"
                   "sub/ignored.edn" "ignored"
                   "sub/ignored.txt" "ignored"
                   "sub/other" "ignored"}]
        (is (some? (fs/create-dirs src)))
        (is (some? (fs/create-dirs sub)))
        (doseq [[p c] files]
          (is (nil? (spit (fs/file (fs/path src p)) c))))
        (is (= dest (sut/copy-with-ignore src dest)))
        (is (not (fs/exists? (fs/path dest "test.txt"))))
        (is (fs/exists? (fs/path dest "included.edn")))
        (is (not (fs/exists? (fs/path dest "sub/ignored.edn"))))
        (is (not (fs/exists? (fs/path dest "sub/ignored.txt"))))
        (is (not (fs/exists? (fs/path dest "sub/other"))))))))
