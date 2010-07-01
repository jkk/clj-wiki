$(function() {
    SyntaxHighlighter.defaults.gutter = false;
    SyntaxHighlighter.defaults.toolbar = false;
    SyntaxHighlighter.defaults.light = true;
    $("pre.code").addClass("code brush: clojure;");
    SyntaxHighlighter.all();
});