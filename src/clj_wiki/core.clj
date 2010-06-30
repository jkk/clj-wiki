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
        [hiccup.page-helpers :only [link-to doctype include-css]]
        [clojure.contrib.string :only [join]])
  (:require [appengine.datastore :as ds]
            [appengine.users :as users])
  (:import com.petebevin.markdown.MarkdownProcessor))

;; config

(def site-title "Clj-Wiki")

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
    (ds/get-entity (ds/create-key "wiki-page" name))
    (catch Exception _
      nil)))

(defn save-wiki-page [name content]
  (ds/create-entity {:kind "wiki-page"
                     :key (ds/create-key "wiki-page" name)
                     :name name
                     :content content}))

;; layout / rendering

(defn render-markdown [txt]
  (.markdown markdown-processor txt))

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

(defn render-page [title & body]
  "Render a page using the given title and body. Title will be escaped,
  body will not."
  (html
   (doctype :html5)
   [:html
    [:head
     [:title (str (h title) " - " (h site-title))]
     (include-css "/css/style.css")]
    [:body
     [:div#page-shell
      [:div#masthead
       [:h1 (link-to "/" (h site-title))]]
      [:div#content-shell
       (render-session-info)
       [:h2#page-title (h title)]
       [:div#content body]]]]]))

(defn render-edit-form [page-name page]
  [:form {:method "POST" :action (uri page-name)}
   [:textarea {:id "edit-text" :name "edit-text"} (:content page)]
   [:input.button {:type "submit" :value "Save"}]])

(defn render-wiki-page [page-name page edit?]
  (render-page
   page-name
   (html
    (if edit?
      (render-edit-form page-name page)
      (if (nil? (:content page))
        [:p.empty "[No content]"]
        (render-markdown (:content page))))
    (when-not edit?
      [:p#page-info
       [:span#page-last-updated "Last updated XXX by YYY"]
       [:span#edit-link.button (link-to (uri page-name {:edit 1})
                                        "Edit page")]]))))

;; request handlers

(defn save-handler [req page-name page]
  (save-wiki-page page-name ((:form-params req) "edit-text"))
  (redirect (uri page-name)))

(defn wiki-handler [req]
  (let [page-name (url-decode (subs (:uri req) 1))
        page (get-wiki-page page-name)]
    (if (= :get (:request-method req))
      (response (render-wiki-page page-name page
                                  ((:query-params req) "edit")))
      (save-handler req page-name page))))

(def wrapped-wiki-handler (-> wiki-handler
                              wrap-params
                              wrap-flash
                              wrap-session
                              wrap-stacktrace
                              users/wrap-with-user-info))

(defservice wrapped-wiki-handler)
