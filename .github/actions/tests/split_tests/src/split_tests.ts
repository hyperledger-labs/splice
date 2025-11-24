import {getInput, setFailed, setOutput} from '@actions/core';
import xmlParser from 'fast-xml-parser';
import fs from 'fs';

type TestTimes = { [name: string]: number };

function getTestSuiteTimesFromXml(testReportsDir: string): TestTimes {

    const options = {
        ignoreAttributes: false
    };
    const parser = new xmlParser.XMLParser(options);
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
                    testTimes[testSuiteName] = testSuiteTime;
                } catch (e) {
                    console.log(`Failed to parse xml report ${file}`)
                }
            }
        });
    } catch (err) {
        console.error(`Warning: could not read test reports from ${testReportsDir}: ${err}`);
    }

    return testTimes;
}

function estimateTestTimes(testTimes: TestTimes, testNames: string[]): TestTimes {
    let maxTestTime = Math.max(...Object.values(testTimes));
    // If maxTestTime is zero, i.e. no runtimes are known, we assign everything an
    // arbitrary non-zero value of 1.0
    maxTestTime = Math.max(maxTestTime, 1.0);

    const estimatedTestTimes: TestTimes = {};
    testNames.forEach(testName => {
        estimatedTestTimes[testName] = testTimes[testName] || maxTestTime;
        // Scalatest actually reported occasionally test times with negative numbers,
        // so we set it to zero in that case.
        estimatedTestTimes[testName] = Math.max(estimatedTestTimes[testName], 0.0);
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
    const sortedTestNames = testNames.sort().sort((a, b) => estimatedTestTimes[a] - estimatedTestTimes[b]);

    const buckets = splitTests(sortedTestNames, estimatedTestTimes, splitTotal);

    buckets.forEach((bucket, i) => {
        console.log(`bucket ${i}: ${bucket.length} tests, total time: ${bucket.reduce((acc, testName) => acc + estimatedTestTimes[testName], 0)}`);
    });
    return buckets;
}

const buckets = computeBuckets(getInput('test_reports_dir'), getInput('test_names_file'), parseInt(getInput('split_total')));
setOutput('test_names', JSON.stringify(buckets));
