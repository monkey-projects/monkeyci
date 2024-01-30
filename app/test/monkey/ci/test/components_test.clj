(ns monkey.ci.test.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys :as bk]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [blob :as b]
             [commands :as co]
             [components :as sut]
             [config :as config]
             [events :as e]
             [git :as git]
             [runners :as r]
             [spec :as spec]
             [storage :as st]]
            [monkey.ci.web
             [handler :as wh]
             [github :as github]]
            [monkey.ci.test.helpers :as h]
            [org.httpkit.server :as http]))

(deftest bus-component
  (testing "`start` creates a bus"
    (is (e/bus? (-> (sut/new-bus)
                    (c/start)))))

  (testing "`stop` destroys the bus"
    (is (nil? (-> (sut/new-bus)
                  (c/start)
                  (c/stop)
                  :pub)))))

(defrecord TestServer []
  http/IHttpServer
  (-server-stop! [s _]
    (assoc s :stopped? true)))

(deftest http-component
  (testing "starts http server"
    (with-redefs [wh/start-server (constantly ::server-started)]

      (is (= ::server-started (-> (sut/new-http-server)
                                  (c/start)
                                  :server)))))

  (testing "stops http server on `stop`"
    (is (nil? (-> (sut/map->HttpServer {:server (->TestServer)})
                  (c/stop)
                  :server)))))

(deftest context
  (testing "contains command"
    (is (= :test-command (:command (sut/new-context :test-command)))))

  (testing "results in context that is compliant to spec"
    (h/with-bus
      (fn [bus]
        (let [ctx (->> (sut/map->Context {:command (constantly "ok")
                                          :event-bus bus
                                          :config config/default-app-config})
                       (c/start))]
          (is (true? (s/valid? ::spec/app-context ctx))
              (s/explain-str ::spec/app-context ctx))))))

  (testing "sets git fn by default, returns checkout dir"
    (let [git-fn (-> (sut/new-context nil)
                     (c/start)
                     :git
                     :fn)
          opts {:url "test-url"
                :branch "test-branch"
                :dir "test-dir"
                :id "test-id"}
          captured-args (atom [])]
      (with-redefs [git/clone (fn [& args]
                                (reset! captured-args args))]
        (is (= "test-dir" (git-fn opts)))
        (is (= [opts] @captured-args)))))

  (testing "sets storage"
    (is (= :test-storage (-> (sut/new-context :test-cmd)
                             (assoc :storage {:storage :test-storage})
                             (c/start)
                             :storage))))

  (testing "sets workspace store"
    (is (b/blob-store? (-> (sut/new-context :test-cmd)
                           (assoc :workspace {:type :disk
                                              :dir "test-dir"})
                           (c/start)
                           :workspace
                           :store))))

  (testing "sets cache store"
    (is (b/blob-store? (-> (sut/new-context :test-cmd)
                           (assoc :cache {:type :disk
                                          :dir "cache-dir"})
                           (c/start)
                           :cache
                           :store))))

  (testing "sets reporter"
    (is (fn? (-> (sut/new-context :test-cmd)
                 (c/start)
                 :reporter))))

  (testing "sets log retriever"
    (is (some? (-> (sut/new-context :test-cmd)
                   (assoc :logging {:type :inherit})
                   (c/start)
                   :logging
                   :retriever))))

  (testing "loads JWK keys"
    (let [[priv pub :as keypaths] (->> ["priv" "pub"]
                                       (map (partial format "dev-resources/test/jwk/%skey.pem")))
          privkey (bk/private-key priv)
          pubkey (bk/public-key pub)
          jwk (-> (sut/new-context :test-cmd)
                  (assoc-in [:config :jwk] (zipmap [:private-key :public-key] keypaths))
                  (c/start)
                  :jwk)]
      (is (not-empty jwk))
      (is (= 2 (count jwk)))
      (is (= privkey (:priv jwk)))
      (is (= pubkey (:pub jwk))))))

(defn- verify-event-handled
  ([ctx evt verifier]
   (h/with-bus
     (fn [bus]
       (is (true? (->> (sut/map->Listeners {:bus bus
                                            :context ctx})
                       (c/start)
                       :handlers
                       (every? e/handler?))))
       (is (true? (e/post-event bus evt)))
       (is (true? (h/wait-until verifier 200))))))
  ([evt verifier]
   (verify-event-handled {:storage (st/make-memory-storage)} evt verifier)))

(defmacro validate-listener [type h]
  `(let [invoked# (atom false)]
     (with-redefs [~h (fn [& _#]
                        (reset! invoked# true))]
       (verify-event-handled {:type ~type}
                             #(deref invoked#)))))

(deftest listeners
  (testing "registers github webhook listener"
    (validate-listener :webhook/github github/prepare-build))

  (testing "registers build runner listener"
    (validate-listener :webhook/validated r/build))

  (testing "registers build triggered listener"
    (validate-listener :build/triggered r/build))

  (testing "registers build completed listener"
    (h/with-memory-store st
      (let [sid ["test-build"]]
        (verify-event-handled
         {:storage st}
         {:type :build/completed
          :build {:sid sid}
          :exit 1}
         (fn []
           (some? (st/find-build-results st sid)))))))

  (testing "unregisters handlers on stop"
    (let [invoked? (atom false)]
      (with-redefs [github/prepare-build (fn [_]
                                           (reset! invoked? true))]
        (h/with-bus
          (fn [bus]
            (is (nil? (->> (sut/map->Listeners {:bus bus})
                           (c/start)
                           (c/stop)
                           :handlers)))
            (is (true? (e/post-event bus {:type :webhook/github})))
            (is (= :timeout (h/wait-until #(deref invoked?) 200)))))))))

(deftest storage
  (testing "creates file storage"
    (h/with-tmp-dir dir
      (let [c (-> (sut/->Storage {:storage {:type :file
                                            :dir dir}})
                  (c/start))]
        (let [s (:storage c)
              l ["test.edn"]]
          (is (some? s))
          (is (some? (st/write-obj s l {:key "value"})))
          (is (true? (st/obj-exists? s l)))))))

  (testing "`stop` removes storage"
    (h/with-tmp-dir dir
      (is (nil? (-> (sut/->Storage {:storage {:type :file
                                              :dir dir}})
                    (c/start)
                    (c/stop)
                    :storage))))))
