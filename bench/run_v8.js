#!/usr/local/bin/node

global.performance = {
  now: function () {
         var t = process.hrtime();
         return t[0] * 1000 + t[1] / 1000000;
       }
}

require("../target/datahike.js");

var tests = process.argv.slice(2);

var round = datahike_bench.core.round;

for (let name of tests) {
  let key = name.replace("-", "_");
  let time = datahike_bench.datahike[key]();
  process.stdout.write(round(time) + "\t");
}

process.stdout.write("\n");