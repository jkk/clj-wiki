(defproject clj-wiki "0.1.0-SNAPSHOT"
  :description "App Engine-based Wiki written in Clojure"
  :author "Justin Kramer"
  :namespaces [clj-wiki.core]
  :dependencies [[org.clojure/clojure "1.2.0-master-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.2.0-SNAPSHOT"]
                 [ring/ring "0.2.3"]
                 [hiccup "0.2.6"]
                 [org.clojars.nakkaya/markdownj "1.0.2b4"]
                 [appengine "0.3-SNAPSHOT"]
                 [com.google.appengine/appengine-api-1.0-sdk "1.3.4"]]
  :dev-dependencies [[leiningen/lein-swank "1.2.0-SNAPSHOT"]
                     [com.google.appengine/appengine-api-labs "1.3.4"]
                     [com.google.appengine/appengine-api-stubs "1.3.4"]
                     [com.google.appengine/appengine-local-runtime "1.3.4"]
                     [com.google.appengine/appengine-testing "1.3.4"]]
  :repositories [["maven-gae-plugin" "http://maven-gae-plugin.googlecode.com/svn/repository"]]
  :compile-path "war/WEB-INF/classes"
  :library-path "war/WEB-INF/lib")
