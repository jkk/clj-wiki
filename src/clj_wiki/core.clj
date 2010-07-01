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
            [appengine.users :as users])
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

;; wiki page model

(defn get-wiki-pages []
  (ds/find-all (ds/query "wiki-page")))

(defn get-wiki-page [name]
  (try
    (let [page (ds/get-entity (ds/create-key "wiki-page" name))]
      (assoc page :content (.getValue (:content page))))
    (catch Exception _
      nil)))

(defn save-wiki-page [name content see]
  (let [ui (users/user-info)]
    (ds/create-entity {:kind "wiki-page"
                       :key (ds/create-key "wiki-page" name)
                       :name name
                       :content (Text. content)
                       :see see
                       :last-updated (.getTime (java.util.Date.))
                       :updated-by (if (:user ui)
                                     (.getEmail (:user ui))
                                     "Anonymous")})))

;; layout / rendering

(defn render-markdown [txt]
  ;; TODO: filter more tags?
  (.markdown markdown-processor
             (-> txt
                 (.replace "<script" "")
                 (.replaceAll "~{3,}((?:.|[\r\n])+?)~{3,}"
                              "<pre class=\"code\">$1</pre>")
                 (.replaceAll ">{3,}((?:.|[\r\n])+?)>{3,}"
                              "<pre class=\"output\">$1</pre>"))))

(defn render-session-info []
  (let [ui (users/user-info)]
    [:div#session-info
     (if-let [user (:user ui)]
       [:div#login-info "Logged in as " [:span#username (.getNickname user)] " "
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
      [:li (link-to (uri ns) ns)])]])

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

(defn render-edit-form [page-name page]
  [:form {:method "POST" :action (uri page-name)}
   [:p "Examples:"]
   [:textarea {:id "edit-text" :name "edit-text"} (h (:content page))]
   [:p "See also (one function per line, namespace-qualified):"]
   [:textarea {:id "see" :name "see"} (h (:see page))]
   [:input.button {:type "submit" :value "Save"}]])

(defn render-wiki-page [page-name page edit?]
  (render-page
   page-name
   (html
    (when-let [arglists (:arglists (meta (resolve (symbol page-name))))]
      [:p#arglists (str arglists)])
    (when-let [doc (:doc (meta (resolve (symbol page-name))))]
      [:p#doc (.replace doc "\n\n" "<br><br>")])
    (if edit?
      (render-edit-form page-name page)
      (if (nil? (:content page))
        [:p.empty "[No examples]"]
        [:div#examples
         [:h3 "Examples"]
         (render-markdown (:content page))]))
    (when-not edit?
      (html
       (when (pos? (count (:see page)))
         [:div#see-shell
          [:h3 "See Also"]
          [:ul#see (for [f (.split #"[\r\n]+" (:see page))]
                     [:li (link-to (uri f) f)])]])
       [:p#page-info
        [:span#page-last-updated
         "Last updated " (:last-updated page)
         " by " (:updated-by page)]
        [:span#edit-link.button (link-to (uri page-name {:edit 1})
                                         "Edit page")]])))))

(defn render-wiki-page-list []
  (let [pages (sort-by :name (get-wiki-pages))]
    (render-page
     "List of All Pages"
     (html
      [:ul#page-list
       (for [page pages]
         [:li (link-to (uri (:name page)) (:name page))])]))))

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
  (let [page-name (url-decode (subs (:uri req) 1))
        page (get-wiki-page page-name)]
    (cond
     (try (ns-publics (symbol page-name))
          (catch Exception _ nil))
     (response (render-ns-list page-name))
     
     (= "list" page-name)
     (response (render-wiki-page-list))
     
     (= :post (:request-method req))
     (save-handler req page-name page)
     
     :else
     (response (render-wiki-page page-name page
                                 ((:query-params req) "edit"))))))

(def wrapped-wiki-handler (-> wiki-handler
                              wrap-params
                              wrap-flash
                              wrap-session
                              wrap-stacktrace
                              users/wrap-with-user-info))

(defservice wrapped-wiki-handler)
