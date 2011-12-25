function() {
  var app = $$(this).app;
  var $_this=$(this);

  place_arrow($_this);

  // First clear all lines
  $_this.parents("ul").children().children().css("font-weight", "");
  $_this.parents("ul").children().css("background-color","white");
  // Then set the clicked lines to bold
  $_this.children().css("font-weight", "bold");
  $_this.css("background-color", "#d0ffd0");

  // Keep this and current bib into app
  app.current_li = $_this;
  app.current_bib = $_this.find("#delete")[0]["bib"]["value"];

  $(this).trigger("change_infos");
}