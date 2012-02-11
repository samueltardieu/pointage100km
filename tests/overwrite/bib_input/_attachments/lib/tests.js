function setup_site_info(app, cb) {
  app.db.saveDoc({
    _id: "_local/site_info",
    site_id: 0
  }, {
    success: function() {
      app.site_id=0;
      cb();
    },
    error: function() {
      app.site_id=0;
      cb();
    }
  });
}
function setup_bib_info(app, cb) {
  app.db.saveDoc({
    _id: infos_id(0),
    dossard: 0,
    course: 1,
  }, {
    success: cb,
    error: cb  });
}
function setup_app(app, cb) {
  fork([
      function(cb) { setup_site_info(app, cb) },
      function(cb) { setup_bib_info(app, cb) }
  ], cb);
}

function open_or_fail(app, doc_id, cb, fail_msg) {
  app.db.openDoc(doc_id, {
    success: cb,
    error: function(status, req, e) {
      ok(false, fail_msg + "," + status
                + "," +req+ "," + e);
      start();
    }});
}

function bib_assert(app, bib, expected_race_id, expected_length, cb) {
  open_or_fail(app, checkpoints_id(bib, app.site_id), function(checkpoints) {
    equal(checkpoints.site_id, app.site_id, "wrong site_id inserted");
    equal(checkpoints.times.length, expected_length, "wrong checkpoints times length");
    equal(checkpoints.race_id, expected_race_id, "wrong race_id inserted");
    cb(checkpoints);
  }, "Error getting freshly inserted checkpoint");
}

function submit_bib_and_assert(app, bib, expected_race_id, expected_length, cb) {
  submit_bib(bib, app, 0, function() {
    bib_assert(app, bib, expected_race_id, expected_length, cb);
  });
}

function submit_remove_checkpoint_and_assert(app, bib, ts, expected_race_id, expected_length, cb) {
  submit_remove_checkpoint(bib, app, ts, function() {
    bib_assert(app, bib, expected_race_id, expected_length, cb)
  });
}

function test_single_bib_insertion(app, bib, expected_race_id) {
  submit_bib_and_assert(app, bib, expected_race_id, 1, function(checkpoints) {
    var ts=checkpoints.times[0];
    submit_remove_checkpoint_and_assert(app, bib, ts, expected_race_id, 0, function(checkpoints) {
      start();
    });
  });
}

function test_bib_input(app) {
  setup_app(app, function() {
    module("bib_input"); 
    test("setup ok", function() {
        expect(1);
        ok(app.site_id != undefined, "undefined site id");
    });
    asyncTest("checkpoints insertion (with infos)", function() {
      test_single_bib_insertion(app, 0, 1);
    });
    asyncTest("checkpoints insertion (without infos)", function() {
      test_single_bib_insertion(app, 999, 0);
    });
  });
};
