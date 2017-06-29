var Tester = require('./test-result-checker');
var tester = new Tester(require('./expected-outcomes').full);

QUnit.testDone(function (details) {
    var testName = details.module + ' ' + details.name;
    if (details.skipped) {
        tester.pending(testName);
    }
    else if (!details.failed) {
        tester.passed(testName);
    }
    else {
        tester.failed(testName);
    }
});

QUnit.done(function (details) {
    details.failed = tester._total - tester._passed;
});
