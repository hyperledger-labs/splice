import os
import xml.etree.ElementTree as ET
from typing import Set, Tuple
from xml.etree.ElementTree import Element, ElementTree
import re
import sys
from pathlib import Path
# Requires Python 3.7+ (f-strings & type hints)
# Please see the main function at the bottom to understand how this script works
from datadog import initialize, api

options = {
    'api_key': os.environ['DATADOG_API_KEY'],
    'api_host': 'https://api.datadoghq.com/'
}
initialize(**options)

metric_detailed_version = "canton_network.failed_test"
metric_short_version = "canton_network.failed_test_grouped"

# sbt generates test-reports in junit xml style that we aggregate into test-reports/<subproject>/<reports> in CircleCI
# through the CircleCI command `upload_test_reports`
# This methods reads those test reports to find out which tests failed
# see also https://www.scala-sbt.org/1.x/docs/Testing.html#Test+Reports
def iterate_through_test_reports():
    failing_tests: Set[Tuple[str, str]] = set()
    for entry in Path('./test-reports').rglob("*.xml"):
        if entry.name.endswith(".xml") and entry.is_file():
            process_test_report(entry, failing_tests)
    return failing_tests


def process_test_report(path: Path, failing_tests: Set[Tuple[str, str]]):
    tree: ElementTree = ET.parse(path)
    root: Element = tree.getroot()
    # To understand this XML parsing I recommend to just go through this code with a debugger on
    # an example test report; alternatively see e.g. https://stackoverflow.com/a/26661423
    for child in root:
        if 'name' not in child.attrib: continue
        contains_fail = any([childchild.tag == 'failure' for childchild in child])
        contains_error = any([childchild.tag == 'error' for childchild in child])
        if contains_fail or contains_error:
            # Example value: LedgerAPIParticipantPruningTestPostgres
            test_name = child.attrib['classname'].split('.')[-1]
            failing_tests.add((metric_short_version, test_name))
            # Example value:
            # LedgerAPIParticipantPruningTestPostgres:Ledger api prevents concurrent access to pruned transaction trees
            full_name = f"{test_name}:{child.attrib['name']}"
            print(f"Found failing test '{full_name}'")
            failing_tests.add((metric_detailed_version, full_name))
    return failing_tests


def check_for_log_failure(failing_tests: Set[Tuple[str, str]]):
    if os.path.exists("found_problems.txt"):
        with open("found_problems.txt", "r") as f:
            line = f.readline()
            print(line)
            # Example log lines that could be read:
            # ERROR i.g.i.ManagedChannelOrphanWrapper - *~*~*~ Channel ManagedChannelImpl{logId=6714, target=localhost:15272} was not shutdown properly!!! ~*~*~*"
            # 2021-05-05 12:43:03,509 [...] WARN  c.d.l.p.s.v.SeedService$ - Trying to gather entropy from the underlying operating system to initialized the contract ID seeding, but the entropy pool seems empty.
            # WARN  c.d.c.p.p.v.ConfirmationResponseFactory:BroadcastPackageUsageIntegrationTest/participant=participant3/domain=da tid:8c53516be1ff7a431b87322eebf2d4ae - Malformed request RequestId(2021-05-05T11:00:30.588840Z). DAMLeError(Error(Contract could not be found with id ContractId(00c0d9eb114b6eec91c8837bad7975c19e0739e7d25cde0c8b9c4446b0cff1a81fca001220a20c40f20f8a329e3874819a5af2e9107e808e8c091d3dd2a4f7aceff24cfcee)))
            # ERROR c.d.c.p.a.BroadcastPackageUsageService:BroadcastPackageUsageIntegrationTest/participant=participant3 tid:ef410c4f0c6bbc7664a1cd07f94644d7 - An unexpected exception occurred while updating UsePackage contracts.

            # If WARN or ERROR is in the line with the error, we report the line from WARN/ERROR onwards
            # Else, we report the whole line
            try:
                name = re.search("((WARN|ERROR).* )-", line).group(1)
            except (IndexError, AttributeError):
                name = line

            # replace 'tid:<32-character-id>' by 'tid:...'
            if "tid:" in name:
                idx = name.index("tid:")
                idx_of_next_space = name[idx:].index(" ")
                name = name[:idx] + "tid:..." + name[idx + idx_of_next_space:]

            print(f"Reporting following name to datadog: '{name}'")
            failing_tests.add((metric_detailed_version, name))
            failing_tests.add((metric_short_version, name[:100]))
    return failing_tests


# Datadog only accepts unicode characters in their tag names
def remove_non_unicode_characters(test_name: str):
    chars = []
    for char in test_name:
        if ord(char) >= 128:
            print(f"Non-unicode character {char} found in test named {test_name}. Converting to '_'")
            char = '_'
        chars.append(char)
    return ''.join(chars)


def format_test_name(test_name: str):
    test_name = remove_non_unicode_characters(test_name)
    if len(test_name) > 200:
        print(f"Truncating {test_name} to 200 characters.")
    return test_name[:200]


def report_to_datadog(metric_name: str, test_name: str):
    send_args = {
        'metric': f'{metric_name}',
        'type': 'count',
        'points': 1,
        'tags': [f"name:{format_test_name(test_name)}",
                 f"branch:{os.environ['CIRCLE_BRANCH']}",
                 f"url:{os.environ['CIRCLE_BUILD_URL']}",
                 f"container_index:{os.environ['CIRCLE_NODE_INDEX']}",
                 f"job:{os.environ['CIRCLE_JOB']}",
                 ]
    }
    resp = api.Metric.send(**send_args)
    if resp != {'status': 'ok'}:
        print(f"Received error response while reporting test '{test_name}': \n {resp}")
        resp2 = api.Metric.send(**send_args)
        print(f"Received following response upon retrying: \n {resp2} ")


if __name__ == "__main__":
    if len(sys.argv) == 2:
        print(f"Reporting following CI failure as a failed test to datadog: {sys.argv[1]}")
        report_to_datadog(metric_name=metric_detailed_version, test_name=sys.argv[1])
        report_to_datadog(metric_name=metric_short_version, test_name=sys.argv[1])
    else:
        print("Starting to iterate through generated test reports")
        failing_tests = iterate_through_test_reports()
        print("Now checking if any log problems were found")
        failing_tests = check_for_log_failure(failing_tests)
        num_detail_reports = len(list(filter(lambda metric_and_test: metric_and_test[0] == metric_detailed_version, failing_tests)))
        print(
            f"Finished iterating through test reports and log problems. In total, found {num_detail_reports} different failed tests "
            f"and found {len(failing_tests)-num_detail_reports} failed tests after slight deduplication for the grouped/summary reporting.")
        print(f"Starting to report failed tests to datadog")
        [report_to_datadog(metric_name, test_name) for (metric_name, test_name) in failing_tests]
        print("Finished reporting failed tests to datadog")
