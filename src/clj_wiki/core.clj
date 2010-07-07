(ns clj-wiki.core
  (:gen-class :extends javax.servlet.http.HttpServlet)
  (:use [ring.util.servlet :only [defservice]]
        [ring.util.response :only [response redirect content-type]]
        [ring.util.codec :only [url-encode url-decode]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.session :only [wrap-session]]
        [ring.middleware.stacktrace :only [wrap-stacktrace]]
        [hiccup.core :only [h html]]
        [hiccup.page-helpers :only [link-to doctype include-css include-js]]
        [clojure.contrib.string :only [join]])
  (:require [appengine.datastore :as ds]
            [appengine.users :as users]
            [appengine.memcache :as mc])
  (:import com.petebevin.markdown.MarkdownProcessor
           [com.google.appengine.api.datastore Text]
           name.fraser.neil.plaintext.diff_match_patch
           name.fraser.neil.plaintext.diff_match_patch$Operation))

;; config

(def site-title "Clojure Examples Wiki")

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
              "clojure.string"
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
         (join "/" (map url-encode (mapcat #(.split % "/" -1) parts)))
         (when-not (empty? params)
           (str "?" (map->query-string params))))))

(defn talk-page? [page-name]
  (.endsWith page-name ":talk"))

(defn fn-page? [page-name]
  (and (not (talk-page? page-name))
       (<= 2 (count (.split page-name "/" -1)))))

(defn now []
  (.getTime (java.util.Date.)))

(defn diff [text1 text2]
  (let [dmp (diff_match_patch.)
        diffs (.diff_main dmp text1 text2)]
    (.diff_cleanupSemantic dmp diffs) ; mutates diffs!
    (for [d diffs]
      (let [text (h (.text d))]
        (condp = (.operation d)
            diff_match_patch$Operation/INSERT [:ins text]
            diff_match_patch$Operation/DELETE [:del text]
            diff_match_patch$Operation/EQUAL text)))))

;; TODO: unique IDs for anonymous?
(defn current-user-id []
  (let [ui (users/user-info)]
    (if (:user ui)
      (.getEmail (:user ui))
      "Anonymous")))

(defn current-user-name [req]
  (or (:display-name (:session req))
      (let [ui (users/user-info req)]
        (if (:user ui)
          (.getNickname (:user ui))
          "Anonymous"))))

(defn logged-in? []
  (:user (users/user-info)))

;; wiki page model

(defn get-wiki-pages []
  (ds/find-all (ds/query "wiki-page")))

(defn get-wiki-pages-history []
  (-> (ds/query "wiki-page-history")
      (ds/order-by :last-updated :desc)
      (ds/find-all)))

(defn get-wiki-page [name & [revision]]
    (try
      (let [key (if revision
                  (ds/create-key "wiki-page-history" (str name " " revision))
                  (ds/create-key "wiki-page" name))
            page (ds/get-entity key)]
        (assoc page :content (.getValue (:content page))))
      (catch Exception _
        nil)))

(defn get-wiki-page-history [name]
  (-> (ds/query "wiki-page-history")
      (ds/filter-by = :name name)
      (ds/order-by :last-updated :desc)
      (ds/find-all)))

(defn get-wiki-page-history-ids [name]
  (into []
        (map #(.getName (:key %))
             (ds/find-all
              (-> (ds/query "wiki-page-history")
                  (ds/filter-by = :name name)
                  (ds/order-by :last-updated :desc))))))

(defn get-wiki-page-ids []
  "Returns a set of all wiki page keys. Faster than getting all entities"
  (into #{}
   (map #(.getName (:key %))
        (ds/find-all
         (doto (ds/query "wiki-page") (.setKeysOnly))))))

(defn save-wiki-page [name input]
  (let [rec {:name name
             :content (Text. (input "edit-text"))
             :see (input "see")
             :last-updated (now)
             :updated-by (input "user-display-name")
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
      (mc/replace-value key (current-user-id) 25)
      (mc/put-value key (current-user-id) 25))))

(defn save-user-preferences [prefs]
  (ds/create-entity {:kind "user"
                     :key (ds/create-key "user" (current-user-id))
                     :id (current-user-id)
                     :display-name (prefs "display-name")}))

(defn get-user-preferences []
  (try
    (ds/get-entity (ds/create-key "user" (current-user-id)))
    (catch Exception _
      {})))

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
    [:span.timestamp
     (.format (java.text.SimpleDateFormat. "d MMM yyyy, HH:mm:ss z")
              (java.util.Date. ts))]))

(defn render-session-info [req]
  (let [ui (users/user-info)]
    [:div#session-info
     (if (logged-in?)
       [:div#login-info "Logged in as "
        [:span#username (link-to (uri "preferences")
                                 (current-user-name req))] " "
        [:span#logout-link.button
         (link-to (.createLogoutURL (:user-service ui) (uri)) "Log out")]]
       [:div#login-info
        [:span#login-link.button
         (link-to (.createLoginURL (:user-service ui) (uri "preferences"))
                  "Log in")]])]))

(defn render-sidebar [req]
  [:div#sidebar
   [:h3 "Namespaces"]
   [:ul#ns-list
    (for [ns ns-list]
      [:li (link-to (uri ns) ns)])]
   [:h3 "Meta"]
   [:ul#meta
    (when (:user (users/user-info))
      [:li (link-to (uri "preferences") "Preferences")])
    [:li (link-to (uri "changes") "Recent Changes")]
    [:li (link-to (uri "guidelines") "Guidelines")]
    [:li (link-to (uri "formatting") "Formatting")]
    [:li (link-to (uri "todo") "To Do")]]])

(defn render-page [req title & body]
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
       (render-session-info req)
       (render-sidebar req)
       [:h2#page-title (h title)]
       [:div#main-content body]
       [:div.clear]]]]]))

(defn render-wiki-page-edit-form [page-name req]
  (let [params (:query-params req)
        page (get-wiki-page page-name (params "revision"))
        other-user (get-other-user-editing page-name)]
    (when-not other-user
      (send-editing-ping page-name))
    (render-page
     req
     page-name
     (html
      (if other-user
        [:p#other-user-notice
         "Another user is currently editing this page."
         " Please wait for them to finish."]
        [:script {:type "text/javascript"}
         (str "setInterval(function() {
                   $.get(\"" (uri page-name {:ping 1}) "\");
               }, 20000);")])
      [:form {:method "POST" :action (uri page-name)}
       (when (fn-page? page-name)
         [:p "Examples:"])
       [:textarea {:id "edit-text" :name "edit-text"} (h (:content page))]
       [:p "See also (one function per line, namespace-qualified):"]
       [:textarea {:id "see" :name "see"} (h (:see page))]
       [:input.button {:type "submit" :value "Save"}]
       [:span#cancel (link-to (uri page-name) "Cancel")]]))))

;; TODO: this function is getting unwieldy
(defn render-wiki-page [page-name page req]
  (let [revision ((:query-params req) "revision")
        [ns-name fn-name] (.split page-name "/" 2)
        var (try (resolve (symbol ns-name fn-name))
                 (catch Exception _ nil))
        ns (find-ns (symbol page-name))]
    (render-page
     req
     (or fn-name page-name)
     (html
      (when (:flash req)
        [:p#flash (:flash req)])
      (when revision
        [:p#revision-notice "This is a revision from "
         (render-timestamp (:last-updated page)) ". "
         (link-to (uri page-name) "View current revision")])
      (when fn-name
        [:p#ns-name (link-to (uri ns-name) ns-name)])
      (when-let [arglists (:arglists (meta var))]
        [:p#arglists (str arglists)])
      (when-let [doc (:doc (meta var))]
        [:p#doc (.replace doc "\n\n" "<br><br>")])
      (when ns
        [:p#ns-doc (:doc (meta ns))])
      (if (empty? (:content page))
        [:p.empty (if fn-name
                    "[No examples]"
                    (if ns "" "[No content]"))]
        [:div#examples
         (when fn-name
           [:h3 "Examples"])
         (render-markdown (:content page))])
      (when-not (empty? (:see page))
        [:div#see-shell
         [:h3 "See Also"]
         [:ul#see (for [f (.split #"[\r\n]+" (:see page))]
                    [:li (link-to (uri f) f)])]])
      (when (and ns (not revision))
        (let [page-ids (get-wiki-page-ids)]
          (html
           [:h3 "All functions"]
           [:ul#ns-functions
            (for [f (sort (map name (keys (ns-publics (symbol page-name)))))]
              [:li {:class (when-not (page-ids (str page-name "/" f))
                             "empty-content")}
               (link-to (uri page-name f) f)])])))
      (if revision
        (html
         [:h3 "Revision Source"]
         [:pre.revision-source (h (:content page))]
         [:pre.revision-source (h (:see page))])
        [:p#page-info
         (if (talk-page? page-name)
           [:span#talk-link.button (link-to (uri (.replace page-name ":talk" ""))
                                            "Back to page")]
           [:span#talk-link.button (link-to (uri (str page-name ":talk"))
                                            "Discuss this page")])
         [:span#edit-link.button (link-to (uri page-name {:edit 1})
                                          "Edit page")]
         (when (:last-updated page)
           [:span#page-last-updated
            (link-to (uri page-name {:history 1})
                     "Last updated " (render-timestamp (:last-updated page))
                     " by " (:updated-by page))])])))))

(defn render-wiki-page-changes [pages req & [title]]
  (render-page
   req
   (or title "Recent Changes")
   (html
    [:table#changes
     [:tr [:th "Page"] [:th "Diff"] [:th "When"] [:th "Who"]]
     (for [page (take history-limit pages)]
       [:tr
        [:td (link-to (uri (:name page)) (h (:name page)))]
        [:td (link-to (uri (:name page) {:diff 1
                                         :new (:last-updated page)})
                      "Diff")]
        [:td (link-to (uri (:name page) {:revision (:last-updated page)})
                      (render-timestamp (:last-updated page)))]
        [:td (:updated-by page)]])])))

(defn render-wiki-page-history [page-name req]
  (render-wiki-page-changes (get-wiki-page-history page-name)
                            req
                            (str page-name " History")))

(defn render-wiki-page-diff [page-name req]
  (let [params (:query-params req)
        page-history-ids (get-wiki-page-history-ids page-name)
        new-page (get-wiki-page page-name (params "new"))
        old-rev (or (params "old")
                    (if-let [old-key (second
                                      (drop-while
                                       #(not= % (str page-name " "
                                                     (:last-updated new-page)))
                                       page-history-ids))]
                      (second (.split old-key " "))))
        old-page (when old-rev
                   (get-wiki-page page-name old-rev))]
    (render-page
     req
     page-name
     (html
      [:p#revision-notice "This is a diff between revision "
       (if-not old-page
         "[None]"
         (link-to (uri page-name {:revision (:last-updated old-page)})
                  (render-timestamp (:last-updated old-page))))
       " and "
       (link-to (uri page-name {:revision (:last-updated new-page)})
                (render-timestamp (:last-updated new-page)))]
      [:pre.diff
       (diff (str (:content old-page) "\n\n" (:see old-page))
             (str (:content new-page) "\n\n" (:see new-page)))]))))

(defn render-wiki-page-list [req]
  (let [pages (sort-by :name (get-wiki-pages))]
    (render-page
     req
     "List of All Pages"
     (html
      [:ul#page-list
       (for [page pages]
         [:li (link-to (uri (:name page)) (:name page))])]))))

(defn render-user-preferences-form [req]
  (render-page
   req
   "Preferences"
   (when (:flash req)
     [:p#flash (:flash req)])
   [:form {:method "POST" :action (uri "preferences")}
    [:p "Display name: "
     [:input {:type "text" :id "display-name" :name "display-name"
              :value (h (current-user-name req))}]]
    [:input.button {:type "submit" :value "Save"}]
    [:span#cancel (link-to (uri) "Cancel")]]))

;; request handlers

(defn save-wiki-page-handler [req page-name]
  (let [params (:form-params req)
        params (assoc params "user-display-name" (current-user-name req))]
    (save-wiki-page page-name params)
    (assoc (redirect (uri page-name))
      :flash "Page saved")))

(defn save-user-preferences-handler [req]
  (let [params (:form-params req)]
    (save-user-preferences params)
    (assoc (redirect (uri "preferences"))
      :flash "Preferences saved")))

(defn wiki-handler [req]
  (let [params (:query-params req)
        page-name (url-decode (subs (:uri req) 1))
        page-name (cond
                   (empty? page-name) "home"
                   (and (empty? params) (:query-string req)) (str page-name "?")
                   (or (= page-name " ") (= page-name "clojure.core/ ")) "+"
                   :else page-name)]
    (if (= :post (:request-method req))
      (if (= "preferences" page-name)
        (save-user-preferences-handler req)
        (save-wiki-page-handler req page-name))
      (cond
        
        (= "list" page-name)
        (response (render-wiki-page-list req))

        (= "changes" page-name)
        (response (render-wiki-page-changes (get-wiki-pages-history) req))

        (= "preferences" page-name)
        (response (render-user-preferences-form req))

        (params "history")
        (response (render-wiki-page-history page-name req))

        (params "diff")
        (response (render-wiki-page-diff page-name req))
        
        (params "edit")
        (response (render-wiki-page-edit-form page-name req))

        (params "ping")
        (response (do (send-editing-ping page-name)
                      "ok"))
        
        :else
        (if-let [page (get-wiki-page page-name (params "revision"))]
          (response (render-wiki-page page-name page req))
          ;; FIXME: needs to check other namespaces
          (if (resolve (symbol "clojure.core" page-name))
            (redirect (uri "clojure.core" page-name))
            (response (render-wiki-page page-name nil req))))))))

(defn wrap-user-preferences [handler]
  (fn [req]
    (let [sess (req :response)]
      (if (or (not (logged-in?))
              (contains? sess :display-name))
        (handler req)
        (let [user-prefs (get-user-preferences)
              req (assoc req :session
                         (assoc sess :display-name
                                (user-prefs :display-name)))
              response (handler req)]
          (assoc response :session
                 (assoc sess :display-name
                        (user-prefs :display-name))))))))

;; TODO: sniff the content type first to make sure it's empty or "text/html"
(defn wrap-utf8
  "The default behavior with Ring and GAE seems to be non-utf8, so change that"
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (if (= 200 (:status resp))
        (content-type resp "text/html; charset=utf-8")
        resp))))

(defn wrap-flash
  "Ring's wrap-flash seems to be broken (it obliterates the existing session)
  so we do our own thing"
  [handler]
  (fn [request]
    (let [session  (request :session)
          flash    (session :_flash)
          session  (dissoc session :_flash)
          request  (assoc request
                     :session session
                     :flash flash)
          response (handler request)
          resp-session (if (contains? response :session)
                         (response :session)
                         session)
          resp-session (if-let [flash (response :flash)]
                         (assoc resp-session :_flash flash)
                         resp-session)]
      (assoc response :session resp-session))))

(def wrapped-wiki-handler (-> wiki-handler
                              wrap-utf8
                              wrap-params
                              wrap-user-preferences
                              wrap-flash
                              wrap-session
                              wrap-stacktrace
                              users/wrap-with-user-info))

(defservice wrapped-wiki-handler)
