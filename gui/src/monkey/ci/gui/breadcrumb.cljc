(ns monkey.ci.gui.breadcrumb
  (:require [medley.core :as mc]
            [monkey.ci.gui.org.db :as cdb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def home {:url "/"
           :name "Home"})

(defn default-breadcrumb [db]
  (let [{:keys [customer-id repo-id build-id job-id] :as p} (r/path-params (r/current db))
        ci (cdb/get-customer db)]
    (cond-> [home]
      customer-id
      (conj {:url (r/path-for :page/customer (select-keys p [:customer-id]))
             :name (:name ci)})
      
      repo-id
      (conj {:url (r/path-for :page/repo (select-keys p [:customer-id :repo-id]))
             :name (->> ci
                        :repos
                        (u/find-by-id repo-id)
                        :name)})

      build-id
      (conj {:url (r/path-for :page/build (select-keys p [:customer-id :repo-id :build-id]))
             :name build-id})

      job-id
      (conj {:url (r/path-for :page/job p)
             :name job-id}))))

(defn- params-breadcrumb [db]
  (conj (default-breadcrumb db)
        {:url (r/path-for :page/customer-params (r/path-params (r/current db)))
         :name "Parameters"}))

(defn- ssh-keys-breadcrumb [db]
  (conj (default-breadcrumb db)
        {:url (r/path-for :page/customer-ssh-keys (r/path-params (r/current db)))
         :name "SSH Keys"}))

(defn- repo-edit-breadcrumb [db]
  (conj (default-breadcrumb db)
        {:url (r/path-for :page/repo-edit (r/path-params (r/current db)))
         :name "Edit"}))

(defn- cust-watch-repo [db]
  (conj (default-breadcrumb db)
        {:url (r/path-for :page/add-repo (r/path-params (r/current db)))
         :name "Watch Repo"}))

(defn- credits-breadcrumb [db]
  (conj (default-breadcrumb db)
        {:url (r/path-for :admin/credits)
         :name "Credits"}))

(defn- cust-credits-breadcrumb [db]
  (mc/insert-nth
   1
   {:url (r/path-for :admin/credits)
    :name "Credits"}
   (default-breadcrumb db)))

(def routes
  "Breadcrumb configuration per route.  If no match is found, the default behaviour
   is applied."
  {:page/customer-params params-breadcrumb
   :page/customer-ssh-keys ssh-keys-breadcrumb
   :page/repo-edit repo-edit-breadcrumb
   :page/add-repo cust-watch-repo
   :admin/credits credits-breadcrumb
   :admin/cust-credits cust-credits-breadcrumb})

(rf/reg-sub
 :breadcrumb/path
 (fn [db _]
   (let [bc (get routes (r/route-name (r/current db)) default-breadcrumb)]
     (bc db))))

(defn breadcrumb
  "Renders breadcrumb component with the given parts.  Each part consists of
   a name and a url."
  [parts]
  [:nav {:aria-label "breadcrumb"}
   (->> (loop [p parts
               r []]
          (let [last? (empty? (rest p))
                v (first p)
                n (if last?
                    [:li.breadcrumb-item.active {:aria-current "page"}
                     (:name v)]
                    [:li.breadcrumb-item 
                     [:a {:href (:url v)} (:name v)]])
                r+ (conj r n)]
            (if last?
              r+
              (recur (rest p) r+))))
        (into [:ol.breadcrumb]))])

(defn path-breadcrumb
  "Renders breadcrumb component according to path.  It uses the current route to
   determine what to display."
  []
  (let [p (rf/subscribe [:breadcrumb/path])]
    [breadcrumb @p]))

