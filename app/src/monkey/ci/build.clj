(ns monkey.ci.build
  "Functions for working with the build object in the runtime.  This
   represents the current build."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [monkey.ci
             [runtime :as rt]
             [storage :as st]
             [utils :as u]]))

(def account->sid (juxt :customer-id :repo-id))

(def build-sid-length 3)

(defn get-sid
  "Gets current build sid from the runtime.  This is either specified directly,
   or taken from account settings."
  [rt]
  (or (get-in rt [:build :sid])
      (let [sid (->> (account->sid (rt/account rt))
                     (take-while some?))]
        (when (= (dec build-sid-length) (count sid))
          sid))))

(defn get-build-id [rt]
  (or (get-in rt [:build :build-id]) "unknown-build"))

(def get-step-sid
  "Creates a unique step id using the build id, pipeline and step from the runtime."
  (comp (partial mapv str)
        (juxt get-build-id
              (comp (some-fn :name :index) :pipeline)
              (comp :index :step))))

(def get-step-id
  "Creates a string representation of the step sid"
  (comp (partial cs/join "-") get-step-sid))

(defn- maybe-set-git-opts [build rt]
  (let [{:keys [git-url branch commit-id dir]} (rt/args rt)]
    (cond-> build
      git-url (merge {:git {:url git-url
                            :branch (or branch "main")
                            :id commit-id}
                      ;; Overwrite script dir cause it will be calculated by the git checkout
                      :script-dir dir}))))

(defn- includes-build-id? [sid]
  (= build-sid-length (count sid)))

(defn make-build-ctx
  "Creates a build context that can be added to the runtime."
  [rt]
  (let [work-dir (rt/work-dir rt)
        orig-sid (or (some->> (:sid (rt/args rt))
                              (u/parse-sid)
                              (take build-sid-length))
                     (get-sid rt))
        ;; Either generate a new build id, or use the one given
        sid (st/->sid (if (or (empty? orig-sid) (includes-build-id? orig-sid))
                        orig-sid
                        (concat orig-sid [(u/new-build-id)])))
        id (or (last sid) (u/new-build-id))]
    (maybe-set-git-opts
     {:build-id id
      :checkout-dir work-dir
      :script-dir (u/abs-path work-dir (rt/get-arg rt :dir))
      :pipeline (rt/get-arg rt :pipeline)
      :sid sid}
     rt)))

(def script-dir "Gets script dir for the build from runtime"
  (comp :script-dir :build))

(def default-script-dir ".monkeyci")

(defn calc-script-dir
  "Given an (absolute) working directory and scripting directory, determines
   the absolute script dir."
  [wd sd]
  (->> (or sd default-script-dir)
       (u/abs-path wd)
       (io/file)
       (.getCanonicalPath)))

(defn- build-related-dir
  ([base-dir-key rt build-id]
   (some-> rt
           (base-dir-key)
           (u/combine build-id)))
  ([base-dir-key rt]
   (build-related-dir base-dir-key rt (get-in rt [:build :build-id]))))

(def checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  (partial build-related-dir (rt/from-config :checkout-base-dir)))

(def ssh-keys-dir
  "Calculates ssh keys dir for the build"
  (partial build-related-dir (rt/from-config :ssh-keys-dir)))

(defn build-completed-result [build exit-code]
  {:build build
   :exit exit-code
   :result (if (zero? exit-code) :success :error)})

(defn build-completed-evt
  "Creates a build completed event"
  [build exit-code & keyvals]
  (cond-> (build-completed-result build exit-code)
    true (assoc :type :build/completed)
    (not-empty keyvals) (merge (apply hash-map keyvals))))

(def step-work-dir
  "Given a context, determines the step working directory.  This is either the
   work dir as configured on the step, or the context work dir, or the process dir."
  (comp
   (memfn getCanonicalPath)
   io/file
   (some-fn (comp :work-dir :step)
            :checkout-dir
            (constantly (u/cwd)))))

(defn step-relative-dir
  "Calculates path `p` as relative to the work dir for the current step"
  [rt p]
  (u/abs-path (step-work-dir rt) p))
