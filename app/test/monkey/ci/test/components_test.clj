(ns monkey.ci.test.components-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as c]
            [monkey.ci
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
        (is (true? (->> (sut/map->Context {:command (constantly "ok")
                                           :event-bus bus
                                           :config config/default-app-config})
                        (c/start)
                        (s/valid? ::spec/app-context)))))))

  (testing "sets git fn by default, returns checkout dir"
    (let [git-fn (-> (sut/new-context nil)
                     (c/start)
                     :git
                     :fn)
          captured-args (atom [])]
      (with-redefs [git/clone+checkout (fn [& args]
                                         (reset! captured-args args))]
        (is (= "test-dir" (git-fn {:url "test-url"
                                   :branch "test-branch"
                                   :dir "test-dir"
                                   :id "test-id"})))
        (is (= ["test-url" "test-branch" "test-id" "test-dir"] @captured-args)))))

  (testing "passes `nil` for missing opts"
    (let [git-fn (-> (sut/new-context nil)
                     (c/start)
                     :git
                     :fn)
          captured-args (atom [])]
      (with-redefs [git/clone+checkout (fn [& args]
                                         (reset! captured-args args))]
        (is (= "test-dir" (git-fn {:url "test-url"
                                   :dir "test-dir"})))
        (is (= ["test-url" nil nil "test-dir"] @captured-args))))))

(defmacro validate-listener [type h]
  `(let [invoked# (atom false)]
     (with-redefs [~h (fn [_#]
                        (reset! invoked# true))]
       (h/with-bus
         (fn [bus#]
           (is (true? (->> (sut/map->Listeners {:bus bus#})
                           (c/start)
                           :handlers
                           (every? e/handler?))))
           (is (true? (e/post-event bus# {:type ~type})))
           (is (true? (h/wait-until #(deref invoked#) 200))))))))

(deftest listeners
  (testing "registers github webhook listener"
    (validate-listener :webhook/github github/prepare-build))

  (testing "registers build runner listener"
    (validate-listener :webhook/validated r/build))

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
              l "test.edn"]
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
