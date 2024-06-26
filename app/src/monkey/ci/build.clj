(ns monkey.ci.build
  "Functions for working with the build object in the runtime.  This
   represents the current build."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [medley.core :as mc]
            [monkey.ci
             [runtime :as rt]
             [sid :as sid]
             [utils :as u]]
            [monkey.ci.build.core :as bc]))

(def account->sid (juxt :customer-id :repo-id))

(def build-sid-length 3)

(def sid
  "Gets the sid from the build"
  :sid)

(def build-id (some-fn :build-id (constantly "unknown-build")))

(defn get-sid
  "Gets current build sid from the runtime.  This is either specified directly,
   or taken from account settings."
  [rt]
  (or (get-in rt [:build sid])
      (let [sid (->> (account->sid (rt/account rt))
                     (take-while some?))]
        (when (= (dec build-sid-length) (count sid))
          sid))))

(def get-build-id (comp build-id rt/build))

(def get-job-sid
  "Creates a job sid using the build id and job id.  Note that this does
   not include the customer and repo ids, so this is only unique within the repo."
  (comp (partial mapv str)
        (juxt get-build-id
              (comp bc/job-id :job))))

(def get-job-id
  "Creates a string representation of the job sid"
  (comp (partial cs/join "-") get-job-sid))

(def rt->job-id (comp bc/job-id :job))

(defn- maybe-set-git-opts
  "If a git url is specified, updates the build with git information, taken from
   the arguments and from runtime."
  [build rt]
  (let [{:keys [git-url branch commit-id dir]} (rt/args rt)
        existing (get-in rt [:build :git])]
    (cond-> build
      git-url (-> (assoc :git (assoc existing
                                     :url git-url
                                     :branch (or branch "main")
                                     :id commit-id))
                  ;; Overwrite script dir cause it will be calculated by the git checkout
                  (assoc-in [:script :script-dir] dir)))))

(defn- includes-build-id? [sid]
  (= build-sid-length (count sid)))

(defn make-build-ctx
  "Creates a build context that can be added to the runtime.  This is used when
   running a build from cli."
  [rt]
  (let [work-dir (rt/work-dir rt)
        orig-sid (or (some->> (:sid (rt/args rt))
                              (sid/parse-sid)
                              (take build-sid-length))
                     (get-sid rt))
        ;; Either generate a new build id, or use the one given
        ;; TODO Get rid of deprecated timestamp based build id and either
        ;; assign a 'local' id, or ask the api to reserve a new index.
        sid (sid/->sid (if (or (empty? orig-sid) (includes-build-id? orig-sid))
                         orig-sid
                         (concat orig-sid [(u/new-build-id)])))
        id (or (last sid) (u/new-build-id))]
    (maybe-set-git-opts
     (merge (get-in rt [rt/config :build])
            {:customer-id (first sid)
             :repo-id (second sid)
             :build-id id
             :checkout-dir work-dir
             :script {:script-dir (u/abs-path work-dir (rt/get-arg rt :dir))}
             :pipeline (rt/get-arg rt :pipeline)
             :sid sid})
     rt)))

(def script "Gets script from the build"
  :script)

(def script-dir
  "Gets script dir from the build"
  (comp :script-dir script))

(defn set-script-dir [b d]
  (assoc-in b [:script :script-dir] d))

(def ^:deprecated rt->script-dir
  "Gets script dir for the build from runtime"
  (comp script-dir rt/build))

(defn- build-related-dir
  ([base-dir-key rt build-id]
   (some-> rt
           (base-dir-key)
           (u/combine build-id)))
  ([base-dir-key rt]
   ;; DEPRECATED Use the 3-arg fn
   (build-related-dir base-dir-key rt (get-in rt [:build :build-id]))))

(defn calc-checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  ([rt build]
   (build-related-dir (rt/from-config :checkout-base-dir) rt (:build-id build)))
  ([rt]
   ;; DEPRECATED Use the 2 arg fun
   (build-related-dir (rt/from-config :checkout-base-dir) rt (get-in rt [:build :build-id]))))

(def checkout-dir
  "Gets the checkout dir as stored in the build structure"
  :checkout-dir)

(defn set-checkout-dir [b d]
  (assoc b :checkout-dir d))

(def ^:deprecated rt->checkout-dir (comp checkout-dir rt/build))

(def default-script-dir ".monkeyci")

(defn calc-script-dir
  "Given an (absolute) working directory and scripting directory, determines
   the absolute script dir."
  [wd sd]
  (->> (or sd default-script-dir)
       (u/abs-path wd)
       (io/file)
       (.getCanonicalPath)))

(def ssh-keys-dir
  "Calculates ssh keys dir for the build"
  (partial build-related-dir (rt/from-config :ssh-keys-dir)))

(defn exit-code->status [exit]
  (when (number? exit)
    (if (zero? exit) :success :error)))

(defn build->evt
  "Prepare build object so it can be added to an event"
  [build]
  (mc/update-existing build :git dissoc :ssh-keys :ssh-keys-dir))

(defn build-end-evt
  "Creates a `build/end` event"
  [build & [exit-code]]
  {:type :build/end
   :sid (:sid build)
   :build (-> build
              (build->evt)
              (assoc :end-time (u/now))
              (mc/assoc-some :status (exit-code->status exit-code)))})

(def ^:deprecated build-completed-evt build-end-evt)

(defn job-work-dir
  "Given a runtime, determines the job working directory.  This is either the
   work dir as configured on the job, or the context work dir, or the process dir."
  [rt]
  (-> (if-let [jwd (get-in rt [:job :work-dir])]
        (if (fs/absolute? jwd)
          jwd
          (if-let [cd (rt->checkout-dir rt)]
            (fs/path cd jwd)
            jwd))
        (or (rt->checkout-dir rt) (u/cwd)))
      (fs/canonicalize)
      (str)))

(defn job-relative-dir
  "Calculates path `p` as relative to the work dir for the current job"
  [rt p]
  (u/abs-path (job-work-dir rt) p))

(def all-jobs "Retrieves all jobs known to the build"
  (comp vals :jobs :script))

(defn success-jobs
  "Returns all successful jobs in the build"
  [b]
  (->> b
       (all-jobs)
       (filter (comp (partial = :success) :status))))

(def minutes
  "msecs per minute"
  60000)

(defn calc-credits
  "Calculates the consumed credits for this build"
  [build]
  (with-precision 2
    (letfn [(job-credits [{cm :credit-multiplier s :start-time e :end-time}]
              (if (every? number? [cm s e])
                (* (bigdec (/ (- e s) minutes)) cm)))]
      (->> build
           (all-jobs)
           (map job-credits)
           (remove nil?)
           (reduce + 0)
           (u/round-up)))))

(defmethod rt/setup-runtime :build [conf _]
  ;; Just copy the build info to the runtime
  (get conf :build))
