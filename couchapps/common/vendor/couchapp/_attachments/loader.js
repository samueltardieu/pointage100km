
function couchapp_load(scripts) {
  for (var i=0; i < scripts.length; i++) {
    document.write('<script src="'+scripts[i]+'"><\/script>')
  };
};

couchapp_load([
  "/_utils/script/sha1.js",
  "/_utils/script/json2.js",
  "/_utils/script/jquery.js",
  "/_utils/script/jquery.couch.js",
  "../common/vendor/couchapp/jquery.couch.app.js",
  "../common/vendor/couchapp/jquery.couch.app.util.js",
  "../common/vendor/couchapp/jquery.mustache.js",
  "../common/vendor/couchapp/jquery.evently.js"
]);
