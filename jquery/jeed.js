(function($) {
  $.fn.jeed = function() {
    this.parent("pre").css({ position: "relative" });
    const button = $("<button>Play</button>")
      .css({
        position: "absolute",
        right: 0,
        bottom: 0
      })
      .on("click", function() {
        console.log("Here");
      });
    this.parent("pre").append(button);
  };
})(jQuery);
