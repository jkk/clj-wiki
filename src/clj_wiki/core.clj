(ns clj-wiki.core
  (:gen-class :extends javax.servlet.http.HttpServlet)
  (:use [ring.util.servlet :only [defservice]]
        [ring.util.response :only [response redirect]]
        [ring.util.codec :only [url-encode url-decode]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.flash :only [wrap-flash]]
        [ring.middleware.stacktrace :only [wrap-stacktrace]]
        [hiccup.core :only [h html]]
        [hiccup.page-helpers :only [link-to doctype include-css include-js]]
        [clojure.contrib.string :only [join]])
  (:require [appengine.datastore :as ds]
            [appengine.users :as users]
            [appengine.memcache :as mc])
  (:import com.petebevin.markdown.MarkdownProcessor
           [com.google.appengine.api.datastore Text]))

;; config

(def site-title "Clj-Wiki")

(def ns-list ["clojure.core"
              "clojure.inspector"
              "clojure.java.browse"
              "clojure.java.io"
              "clojure.java.javadoc"
              "clojure.java.shell"
              "clojure.main"
              "clojure.pprint"
              "clojure.repl"
              "clojure.set"
              "clojure.stacktrace"
              "clojure.template"
              "clojure.test"
              "clojure.walk"
              "clojure.xml"
              "clojure.zip"])

(def history-limit 100)

(def markdown-processor (MarkdownProcessor.))

;; utilities

(defn map->query-string [m]
  "Turns a map into a query string, stringifying and encoding as necessary"
  (join "&" (for [[k v] m]
              (let [k (if (keyword? k) (name k) (str k))]
                (str (url-encode k) "=" (url-encode (str v)))))))

(defn uri [& parts+params]
  "Creates an absolute URI from given path parts and an optional map of
  query params."
  (let [[parts [params]] (split-with string? parts+params)]
    (str "/"
         (join "/" (map url-encode (mapcat #(.split % "/") parts)))
         (when (pos? (count params))
           (str "?" (map->query-string params))))))

(defn now []
  (.getTime (java.util.Date.)))

;; TODO: unique IDs for anonymous?
(defn current-user-id []
  (let [ui (users/user-info)]
    (if (:user ui)
      (.getEmail (:user ui))
      "Anonymous")))

(defn current-user-name []
  (let [ui (users/user-info)]
    (if (:user ui)
      (.getNickname (:user ui))
      "Anonymous")))


;; wiki page model

(defn get-wiki-pages []
  (ds/find-all (ds/query "wiki-page")))

(defn get-wiki-pages-by-date []
  (-> (ds/query "wiki-page-history")
      (ds/order-by :last-updated :desc)
      (ds/find-all)))

(defn get-wiki-page [name & [revision]]
  (let [key (if revision
              (ds/create-key "wiki-page-history" (str name " " revision))
              (ds/create-key "wiki-page" name))]
    (try
      (let [page (ds/get-entity key)]
        (assoc page :content (.getValue (:content page))))
      (catch Exception _
        nil))))

(defn get-wiki-page-history [name]
  (-> (ds/query "wiki-page-history")
      (ds/filter-by = :name name)
      (ds/order-by :last-updated :desc)
      (ds/find-all)))

(defn save-wiki-page [name content see]
  (let [rec {:name name
             :content (Text. content)
             :see see
             :last-updated (now)
             :updated-by (current-user-name)
             :updated-by-id (current-user-id)}]
    (ds/create-entity
     (merge rec {:kind "wiki-page"
                 :key (ds/create-key "wiki-page" name)}))
    (ds/create-entity
     (merge rec {:kind "wiki-page-history"
                 :key (ds/create-key "wiki-page-history"
                                     (str name " " (:last-updated rec)))}))))

(defn get-other-user-editing [page-name]
  (let [other-user (mc/get-value (str "editing:" page-name))]
    (and other-user (not= other-user (current-user-id)))))

(defn send-editing-ping [page-name]
  (let [key (str "editing:" page-name)
        stamp (mc/get-value key)]
    (if stamp
      (mc/replace-value key (current-user-id) 10)
      (mc/put-value key (current-user-id) 10))))

;; layout / rendering

(defn render-markdown [txt]
  ;; TODO: filter more tags?
  (.markdown markdown-processor
             (-> txt
                 (.replace "<script" "")
                 ;; code block
                 (.replaceAll "~{3,}((?:.|[\r\n])+?)~{3,}"
                              "<pre class=\"code\">$1</pre>")
                 ;; output block
                 (.replaceAll ">{3,}((?:.|[\r\n])+?)>{3,}"
                              "<pre class=\"output\">$1</pre>"))))

(defn render-timestamp [ts]
  (when ts
    (.format (java.text.SimpleDateFormat. "d MMM yyyy, hh:mm aaa z")
             (java.util.Date. ts))))

(defn render-session-info []
  (let [ui (users/user-info)]
    [:div#session-info
     (if-let [user (:user ui)]
       [:div#login-info "Logged in as " [:span#username (current-user-name)] " "
        [:span#logout-link.button
         (link-to (.createLogoutURL (:user-service ui) "/") "Log out")]]
       [:div#login-info
        [:span#login-link.button
         (link-to (.createLoginURL (:user-service ui) "/") "Log in")]])]))

(defn render-sidebar []
  [:div#sidebar
   [:h3 "Namespaces"]
   [:ul#ns-list
    (for [ns ns-list]
      [:li (link-to (uri ns) ns)])]
   [:h3 "Meta"]
   [:ul#meta
    [:li (link-to (uri "changes") "Recent Changes")]]])

(defn render-page [title & body]
  "Render a page using the given title and body. Title will be escaped,
  body will not."
  (html
   (doctype :html5)
   [:html
    [:head
     [:title (str (h title) " - " (h site-title))]
     (include-css "/css/style.css"
                  "/css/shCore.css"
                  "/css/shThemeDefault.css")
     (include-js "/js/jquery.js"
                 "/js/shCore.js"
                 "/js/shBrushClojure.js"
                 "/js/main.js")]
    [:body
     [:div#page-shell
      [:div#masthead
       [:h1 (link-to "/" (h site-title))]]
      [:div#content-shell
       (render-session-info)
       (render-sidebar)
       [:h2#page-title (h title)]
       [:div#main-content body]
       [:div.clear]]]]]))

(defn render-wiki-page-edit-form [page-name page]
  (let [other-user (get-other-user-editing page-name)]
    (when-not other-user
      (send-editing-ping page-name))
    (render-page
     page-name
     (html
      (when other-user
        [:p#other-user-notice
         "Another user is currently editing this page."
         " Please wait for them to finish."])
      [:form {:method "POST" :action (uri page-name)}
       [:p "Examples:"]
       [:textarea {:id "edit-text" :name "edit-text"} (h (:content page))]
       [:p "See also (one function per line, namespace-qualified):"]
       [:textarea {:id "see" :name "see"} (h (:see page))]
       [:input.button {:type "submit" :value "Save"}]
       [:span#cancel (link-to (uri page-name) "Cancel")]]))))

(defn render-wiki-page [page-name page & [revision]]
  (render-page
   page-name
   (html
    (when revision
      [:p#revision-notice "This is a revision from "
       (render-timestamp (:last-updated page)) ". "
       (link-to (uri page-name) "View current revision")])
    (when-let [arglists (:arglists (meta (resolve (symbol page-name))))]
      [:p#arglists (str arglists)])
    (when-let [doc (:doc (meta (resolve (symbol page-name))))]
      [:p#doc (.replace doc "\n\n" "<br><br>")])
    (if (zero? (count (:content page)))
      [:p.empty "[No examples]"]
      [:div#examples
       [:h3 "Examples"]
       (render-markdown (:content page))])
    (when (pos? (count (:see page)))
      [:div#see-shell
       [:h3 "See Also"]
       [:ul#see (for [f (.split #"[\r\n]+" (:see page))]
                  [:li (link-to (uri f) f)])]])
    (if revision
      (html
       [:h3 "Revision Source"]
       [:pre.revision-source (h (:content page))]
       [:pre.revision-source (h (:see page))])
      [:p#page-info
       [:span#edit-link.button (link-to (uri page-name {:edit 1})
                                        "Edit page")]
       (when (:last-updated page)
         [:span#page-last-updated
          (link-to (uri page-name {:history 1})
                   "Last updated " (render-timestamp (:last-updated page))
                   " by " (:updated-by page))])]))))

(defn render-wiki-page-history [page-name page]
  (render-wiki-page-changes (get-wiki-page-history page-name)
                            (str page-name " History")))

(defn render-wiki-page-list []
  (let [pages (sort-by :name (get-wiki-pages))]
    (render-page
     "List of All Pages"
     (html
      [:ul#page-list
       (for [page pages]
         [:li (link-to (uri (:name page)) (:name page))])]))))

(defn render-wiki-page-changes [pages & [title]]
  (render-page
   (or title "Recent Changes")
   (html
    [:table#changes
     [:tr [:th "Page"] [:th "When"] [:th "Who"]]
     (for [page (take history-limit pages)]
       [:tr
        [:td (link-to (uri (:name page) {:revision (:last-updated page)})
                      (:name page))]
        [:td (render-timestamp (:last-updated page))]
        [:td (:updated-by page)]])])))

(defn render-ns-list [ns]
  (render-page
   ns
   (html
    [:ul#ns-functions
     (for [f (sort (map name (keys (ns-publics (symbol ns)))))]
       [:li (link-to (uri ns f) f)])])))

;; request handlers

(defn save-handler [{params :form-params} page-name page]
  (save-wiki-page page-name (params "edit-text") (params "see"))
  (redirect (uri page-name)))

(defn wiki-handler [req]
  (let [params (:query-params req)
        page-name (url-decode (subs (:uri req) 1))
        page (get-wiki-page page-name (params "revision"))]
    (if (= :post (:request-method req))
      (save-handler req page-name page)
      (response
       (cond
        
        (zero? (count page-name))
        (response (render-page "Home" [:p "Nothing here yet!"]))

        (when (pos? (count page-name))
          (try (ns-publics (symbol page-name))
               (catch Exception _ nil)))
        (render-ns-list page-name)
     
        (= "list" page-name)
        (render-wiki-page-list)

        (= "changes" page-name)
        (render-wiki-page-changes (get-wiki-pages-by-date))
     
        (params "history")
        (render-wiki-page-history page-name page)

        (params "edit")
        (render-wiki-page-edit-form page-name page)
        
        :else
        (render-wiki-page page-name page (params "revision")))))))

(def wrapped-wiki-handler (-> wiki-handler
                              wrap-params
                              wrap-flash
                              wrap-session
                              wrap-stacktrace
                              users/wrap-with-user-info))

(defservice wrapped-wiki-handler)
