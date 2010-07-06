# clj-wiki

Google App Engine-based Wiki written in Clojure

## Usage

Local REPL development:

    ;; Eval clj-wiki.local and clj.wiki.core, then...

    (clj-wiki.local/start-server wrapped-wiki-handler)
    (clj-wiki.local/login "test@example.com" true)

    ;; Open http://localhost:8181/

Local viewing with App Engine tools:

    bin/compile
    bin/start

Then open http://localhost:8080/.

To deploy to GAE:

    bin/deploy

## Acknowledgements

Code for doing local REPL-based development on App Engine largely taken from http://github.com/christianberg/compojureongae.

Diff lib: http://code.google.com/p/google-diff-match-patch/

## License

Copyright (C) 2010 Justin Kramer

Distributed under the Eclipse Public License, the same as Clojure.
