function call_with_checkpoints(bib, app, f) {
  app.db.view("bib_input/contestant-checkpoints", {
    key: bib,
    success: function(data) {
      var checkpoints = (data["rows"][0] && data["rows"][0]["value"]) || {};
      f.call(null, checkpoints);
    }
  });
}

function new_checkpoints(bib) {
        var checkpoints = {};
        checkpoints.bib = bib;
        checkpoints.times = [];
        checkpoints.site_id = 0;
        return checkpoints;
}

function add_checkpoint(checkpoints) {
        var ts = new Date().getTime();
        checkpoints["times"].push(ts);
}

function remove_checkpoint(checkpoints, ts) {
	$.log("removing " + ts + " in " + checkpoints["times"]);
	//Why doesn't indexOf work ?!?
	for (var i = checkpoints["times"].length-1; i>=0; i--) {
		if (ts == checkpoints["times"][i]) {
			checkpoints["times"].splice(i, 1);
			return;
		}
	}
}
