$(function() {
    $(".timestamp").each(function() {
        var date = new Date(this.innerHTML);
        this.innerHTML = date.toDateString() + ", " + date.toLocaleTimeString();
    });
    SyntaxHighlighter.defaults.gutter = false;
    SyntaxHighlighter.defaults.toolbar = false;
    SyntaxHighlighter.defaults.light = true;
    $("pre.code").addClass("code brush: clojure;");
    SyntaxHighlighter.all();
});