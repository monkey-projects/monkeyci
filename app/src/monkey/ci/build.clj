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
             [time :as t]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.core :as ec]))

(def default-script-dir ".monkeyci")

(def sid-props [:org-id :repo-id :build-id])

(def account->sid (juxt :org-id :repo-id))
(def props->sid
  "Constructs sid from build properties"
  (apply juxt sid-props))

(def build-sid-length 3)

(def sid
  "Gets the sid from the build"
  (some-fn :sid props->sid))

(def org-id (some-fn :org-id (comp first :sid)))
(def sid->org-id first)

(def build-id (some-fn :build-id (constantly "unknown-build")))

(defn get-job-sid
  "Creates a job sid using the build id and job id.  Note that this does
   not include the customer and repo ids, so this is only unique within the repo."
  [job build]
  (->> [(build-id build) (bc/job-id job)]
       (mapv str)))

(def rt->job-id (comp bc/job-id :job))

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

(defn- maybe-set-git-opts
  "If a git url is specified, updates the build with git information, taken from
   the arguments and from runtime."
  [build args]
  (let [{:keys [git-url branch tag commit-id dir]} args]
    (cond-> build
      git-url (-> (assoc :git (-> {:url git-url
                                   :branch (or branch "main")
                                   :id commit-id}
                                  (mc/assoc-some :tag tag)))
                  ;; Overwrite script dir cause it will be calculated by the git checkout
                  (assoc-in [:script :script-dir] dir)))))

(defn local-build-id []
  (str "local-build-" (System/currentTimeMillis)))

(defn- build-related-dir
  [base-dir-key rt build-id]
  (some-> rt
          (base-dir-key)
          (u/combine build-id)))

(defn calc-checkout-dir
  "Calculates the checkout directory for the build, by combining the checkout
   base directory and the build id."
  [rt build]
  (some-> (get-in rt [rt/config :checkout-base-dir])
          (u/combine (:build-id build))))

(def checkout-dir
  "Gets the checkout dir as stored in the build structure"
  :checkout-dir)

(defn set-checkout-dir [b d]
  (assoc b checkout-dir d))

(def credit-multiplier :credit-multiplier)

(defn set-credit-multiplier [b cm]
  (assoc b credit-multiplier cm))

(def ^:deprecated rt->checkout-dir (comp checkout-dir rt/build))

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
  (if (and (number? exit) (zero? exit))
    :success
    :error))

(defn build-evt [type build & keyvals]
  (apply ec/make-event type :sid (sid build) keyvals))

(defn- with-build-evt [type build]
  (build-evt type
             build
             :build build))

(defn build-triggered-evt [build]
  (-> (with-build-evt :build/triggered build)
      ;; sid at this point is only repo sid, since the build id still needs to be assigned
      (assoc :sid [(:org-id build) (:repo-id build)])))

(defn build-pending-evt [build]
  (with-build-evt :build/pending build))

(defn build-init-evt [build]
  (with-build-evt :build/initializing build))

(defn build-start-evt [build]
  (build-evt :build/start
             build
             :credit-multiplier (credit-multiplier build)))

(defn build-end-evt
  "Creates a `build/end` event"
  [build & [exit-code]]
  (-> (build-evt :build/end build)
      (assoc :status (exit-code->status exit-code)
             ;; TODO Remove this
             :build (-> build
                        (assoc :end-time (u/now))
                        (mc/update-existing :git dissoc :ssh-keys)
                        (mc/assoc-some :status (exit-code->status exit-code))))
      (mc/assoc-some :message (:message build))))

(defn job-work-dir
  "Given a job and a build, determines the job working directory.  This is either the
   work dir as configured on the job, or the build checkout dir, or the process dir."
  [job checkout-dir]
  (-> (if-let [jwd (:work-dir job)]
        (if (fs/absolute? jwd)
          jwd
          (if checkout-dir
            (fs/path checkout-dir jwd)
            jwd))
        (or checkout-dir (u/cwd)))
      (fs/canonicalize)
      (str)))

(defn job-relative-dir
  "Calculates path `p` as relative to the work dir for the current job"
  [job checkout-dir p]
  (u/abs-path (job-work-dir job checkout-dir) p))

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
