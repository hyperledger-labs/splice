import {getInput, setOutput} from '@actions/core';
import {XMLParser} from 'fast-xml-parser';
import fs from 'fs';

type TestTimes = { [name: string]: number };

// Estimate for suites without a trusted report (new suites, or only failed runs cached).
const UNKNOWN_TEST_TIME_SECONDS = 300.0;

// Only reports of suites that ran and passed say how long a suite takes: a failed or
// aborted suite usually stops early, and its time would skew the split.
function isTrustedReport(testsuite: any, time: number): boolean {
    const tests = parseInt(testsuite['@_tests']);
    const failures = parseInt(testsuite['@_failures'] ?? '0');
    const errors = parseInt(testsuite['@_errors'] ?? '0');
    const skipped = parseInt(testsuite['@_skipped'] ?? '0');
    return tests > 0 && failures === 0 && errors === 0 && skipped < tests && time > 0;
}

function getTestSuiteTimesFromXml(testReportsDir: string): TestTimes {

    const options = {
        ignoreAttributes: false
    };
    const parser = new XMLParser(options);
    const testTimes: TestTimes = {};
    try {
        fs.readdirSync(testReportsDir).forEach(file => {
            if (file.endsWith('.xml')) {
                try {
                    console.log(`Parsing xml report ${file}`)
                    const path = `${testReportsDir}/${file}`;
                    const XMLdata = fs.readFileSync(path);
                    const parsed = parser.parse(XMLdata);
                    const testSuiteName = parsed.testsuite['@_name'];
                    const testSuiteTime = parseFloat(parsed.testsuite['@_time']);
                    if (isTrustedReport(parsed.testsuite, testSuiteTime)) {
                        testTimes[testSuiteName] = testSuiteTime;
                    } else {
                        console.log(`Ignoring untrusted report ${file} (tests=${parsed.testsuite['@_tests']}, failures=${parsed.testsuite['@_failures']}, errors=${parsed.testsuite['@_errors']}, skipped=${parsed.testsuite['@_skipped']}, time=${testSuiteTime})`);
                    }
                } catch (e) {
                    console.error(`Failed to parse xml report ${file}`)
                }
            }
        });
    } catch (err) {
        console.error(`Warning: could not read test reports from ${testReportsDir}: ${err}`);
    }

    return testTimes;
}

function estimateTestTimes(testTimes: TestTimes, testNames: string[]): TestTimes {
    const estimatedTestTimes: TestTimes = {};
    testNames.forEach(testName => {
        estimatedTestTimes[testName] = testTimes[testName] ?? UNKNOWN_TEST_TIME_SECONDS;
    });

    return estimatedTestTimes
}

function splitTests(sortedTestNames: string[], estimatedTestTimes: TestTimes, splitTotal: number): string[][] {
    const bucketTimes = Array(splitTotal).fill(0);
    const buckets = Array.from(Array(splitTotal), () => new Array())


    sortedTestNames.forEach(testName => {
        const minBucketIndex = bucketTimes.indexOf(Math.min(...bucketTimes));
        bucketTimes[minBucketIndex] += estimatedTestTimes[testName];
        buckets[minBucketIndex].push(testName);

        console.log(`added ${testName} to bucket ${minBucketIndex}, total time: ${bucketTimes[minBucketIndex]}`);
        console.log(`bucket ${minBucketIndex} has ${buckets[minBucketIndex].length} tests`);
    });

    return buckets;
}

function computeBuckets(testReportsDir: string, testNamesFile: string, splitTotal: number) {
    const testTimes = getTestSuiteTimesFromXml(testReportsDir);

    const testNames = fs.readFileSync(testNamesFile).toString().split('\n').filter(name => name.length > 0);

    const estimatedTestTimes = estimateTestTimes(testTimes, testNames);

    // Build a sorted list of test names, sorted by their estimated test time.
    // We first sort alphabetically, so that tests with the same estimated time
    // are sorted in a deterministic way.
    const sortedTestNames = testNames.sort().sort((a, b) => estimatedTestTimes[a] - estimatedTestTimes[b]).reverse();

    const buckets = splitTests(sortedTestNames, estimatedTestTimes, splitTotal);

    buckets.forEach((bucket, i) => {
        console.log(`bucket ${i}: ${bucket.length} tests, total time: ${bucket.reduce((acc, testName) => acc + estimatedTestTimes[testName], 0)}`);
    });
    return buckets;
}

const buckets = computeBuckets(getInput('test_reports_dir'), getInput('test_names_file'), parseInt(getInput('split_total')));
setOutput('test_names', JSON.stringify(buckets));
