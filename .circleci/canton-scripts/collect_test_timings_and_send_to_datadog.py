# This script is adapted from "collect_failing_tests_and_send_to_datadog.py", which itself is taken from Canton's repository

# This script exposes two custom metrics to Datadog:
#   1. canton_network.ci_timings.testsuites -
#       This scrapes timing information from SBT's test-report files (in xml format) at the testsuite level
#       This is a "gauge" metric is tagged by `branch` and `testsuite` for queries/aggregation
#   2. canton_network.ci_timings.jobs -
#       This reports the overall timing of individual jobs in a workflow
#       This is also a "gauge" metric tagged by `branch` and `job`
import os
import re
import time
import xml.etree.ElementTree as ET
from xml.etree.ElementTree import Element, ElementTree
from pathlib import Path
# Requires Python 3.7+ (f-strings & type hints)
# Please see the main function at the bottom to understand how this script works
from datadog import initialize, api

options = {
    'api_key': os.environ['DATADOG_API_KEY'],
    'api_host': 'https://api.datadoghq.com/'
}
initialize(**options)

metric_name_testsuite_timings = "canton_network.ci_timings.testsuites"
metric_name_job_timings = "canton_network.ci_timings.jobs"

# sbt generates test-reports in junit xml style that we aggregate into test-reports/<subproject>/<reports> in CircleCI
# through the CircleCI command `upload_test_reports`
# This methods reads those test reports to find out which tests failed
# see also https://www.scala-sbt.org/1.x/docs/Testing.html#Test+Reports
def iterate_through_test_reports():
    test_timings = list()
    for entry in Path('./test-reports').rglob("*.xml"):
        if entry.name.endswith(".xml") and entry.is_file():
            test_timings.append(process_test_report(entry))
    return test_timings

# look for a file written on disk by a CI job with a timestamp of its starting time
# return
def read_job_timing():
    job_start_time = open(Path(Path.home(), "job_start_time.txt"), "r")
    start_timestamp = int(job_start_time.read())
    job_start_time.close()

    time_elapsed = time.time() - start_timestamp
    return time_elapsed

def process_test_report(path: Path):
    tree: ElementTree = ET.parse(path)
    root: Element = tree.getroot()

    return dict({ 'name': root.get('name'), 'time': float(root.get('time')) })

# Datadog only accepts unicode characters in their tag names
def remove_non_unicode_characters(test_name: str):
    chars = []
    for char in test_name:
        if ord(char) >= 128:
            print(f"Non-unicode character {char} found in test named {test_name}. Converting to '_'")
            char = '_'
        chars.append(char)
    return ''.join(chars)

def remove_java_namespace_prefix(test_name: str):
    return test_name.split(".")[-1]

def format_test_name(test_name: str):
    test_name = remove_non_unicode_characters(test_name)

    # unless there's a reason to show the full namespace, they just clutter the Datadog UI
    test_name = remove_java_namespace_prefix(test_name)

    if len(test_name) > 200:
        print(f"Truncating {test_name} to 200 characters.")
    return test_name[:200]

# CircleCI adds "-[d]" number suffixes if the same job is specified multiple times in the same pipeline
# Since its the same job, we want to consolidate the metrics
def remove_number_suffixes(job_name: str):
    pattern = re.compile('(.*)(-\d*)$')
    match = pattern.search(job_name)

    if match is None:
        return job_name
    else:
        # Recursive because for some reason, multiple suffixes can appear (e.g. "docker_images_push-2-1")
        return remove_number_suffixes(match.group(1))

def format_job_name(job_name: str):
    job_name = remove_number_suffixes(job_name)

    if len(job_name) > 200:
        print(f"Truncating {job_name} to 200 characters.")
    return job_name[:200]

def send_to_datadog(metric: str, points: float, tags: list[str]):
    send_args = {
        'metric': metric,
        'type': 'gauge',
        'points': points,
        'tags': tags
    }

    return api.Metric.send(**send_args)

# integration test timing
def report_itt_to_datadog(metric_name: str, test_name: str, points: float):
    tags = [f'testsuite:{format_test_name(test_name)}', f"branch:{os.environ['CIRCLE_BRANCH']}"]
    resp = send_to_datadog(metric_name, points, tags)
    if resp != {'status': 'ok'}:
        print(f"Received error response while reporting test '{test_name}': \n {resp}")
        resp2 = send_to_datadog(metric_name, points, tags)
        print(f"Received following response upon retrying: \n {resp2} ")

# job timing
def report_jt_to_datadog(metric_name: str, job_name: str, points: float):
    tags = [f'job:{format_job_name(job_name)}', f"branch:{os.environ['CIRCLE_BRANCH']}"]
    resp = send_to_datadog(metric_name, points, tags)
    if resp != {'status': 'ok'}:
        print(f"Received error response while reporting job timing for '{job_name}': \n {resp}")
        resp2 = send_to_datadog(metric_name, points, tags)
        print(f"Received following response upon retrying: \n {resp2} ")

if __name__ == "__main__":
    print("Retrieving timing data from sbt test reports")
    results = iterate_through_test_reports()

    if (len(results) == 0):
        print("No test reports found, skipping...")
    else:
        print("Received test report timing data:", results)
        print("Submitting sbt test timing data to datadog...")
        [report_itt_to_datadog(
            metric_name_testsuite_timings,
            testsuite["name"],
            testsuite["time"]
            ) for testsuite in results]

    print("Looking for job timing")
    total_job_time = read_job_timing()
    print("Found total job time elapsed (seconds):", total_job_time)
    print("Submitting job timing data to datadog...")
    report_jt_to_datadog(metric_name_job_timings, os.environ['CIRCLE_JOB'], total_job_time)

    print("Test timing reporting completed.")
