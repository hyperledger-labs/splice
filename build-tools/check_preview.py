#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Script to display Pulumi preview outputs from CircleCI jobs in a readable format.

Usage:
  ./check_preview.py JOB_ID_OR_URL [--verbose] [--output-file OUTPUT_FILE] [--hide-config]
                                   [--hide-env] [--gh-org GH_ORG] [--gh-repo GH_REPO]
                                   [--include-ops OPERATIONS] [--exclude-ops OPERATIONS]
                                   [--include-types RESOURCE_TYPES] [--exclude-types RESOURCE_TYPES]
                                   [--include-id-pattern PATTERNS] [--exclude-id-pattern PATTERNS]
                                   [--summary] [--count-only]

Arguments:
  JOB_ID_OR_URL        CircleCI job ID or full URL
  --verbose            Show detailed diagnostic information during processing
  --output-file        File to save the raw output to (default: pulumi_preview_output.txt)
  --hide-config        Redact configuration blocks that start with 'Loaded'
  --hide-env           Hide environment lines, flags, and all resource read operations including metadata
  --hide-grafana       Hide Grafana dashboard changes (ConfigMaps with observability/cn-grafana in ID)

Filtering Options:
  --include-ops        Only show specified operations (create,read,update,delete,replace)
                       Example: --include-ops create,update
  --exclude-ops        Hide specified operations (create,read,update,delete,replace)
                       Example: --exclude-ops read,delete
  --include-types      Only show resources matching specified types (supports wildcards)
                       Example: --include-types kubernetes:apps/v1:Deployment,kubernetes:core/v1:Service
  --exclude-types      Hide resources matching specified types (supports wildcards)
                       Example: --exclude-types kubernetes:networking.*,aws:*

  --include-id-pattern Only show resources with IDs matching patterns (supports wildcards)
                       Example: --include-id-pattern multi-validator/*
  --exclude-id-pattern Hide resources with IDs matching patterns (supports wildcards)
                       Example: --exclude-id-pattern */grafana-*

Output Options:
  --summary           Show only a summary of changes by resource type
  --count-only        Show only the count of resources by operation type

  --gh-org            GitHub organization name (default: DACH-NY)
  --gh-repo           GitHub repository name (default: canton-network-internal)
"""

import argparse
import os
import re
import sys
import json
import requests
from termcolor import colored
from enum import Enum, auto
from dataclasses import dataclass, field
from collections import defaultdict
from typing import List, Dict, Optional, Tuple

# Pattern to match all ANSI escape sequences
ANSI_ESCAPE_PATTERN = r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])'

# Resource operation types
class ResourceOp(Enum):
    CREATE = auto()
    READ = auto()
    UPDATE = auto()
    DELETE = auto()
    REPLACE = auto()
    UNKNOWN = auto()

# Resource metadata entry
@dataclass
class MetadataEntry:
    key: str
    value: str
    original_line: str

# Resource representation
@dataclass
class Resource:
    operation: ResourceOp
    resource_type: str
    metadata: List[MetadataEntry] = field(default_factory=list)
    header_line: Optional[str] = None
    header_line_idx: int = -1
    metadata_lines: List[Tuple[int, str]] = field(default_factory=list)
    content_lines: List[Tuple[int, str]] = field(default_factory=list)  # Non-metadata content lines (changes, etc.)
    all_lines: List[int] = field(default_factory=list)  # All line indices that belong to this resource

    def should_display(self, filter_args):
        """
        Determine if this resource should be displayed based on filter arguments.

        Args:
            filter_args: Filter arguments from command line

        Returns:
            True if the resource should be displayed, False otherwise
        """
        return resource_matches_filter(self, filter_args)

    def __post_init__(self):
        if self.metadata is None:
            self.metadata = []
        if self.metadata_lines is None:
            self.metadata_lines = []
        if self.content_lines is None:
            self.content_lines = []
        if self.all_lines is None:
            self.all_lines = []

    def should_hide(self, hide_env: bool) -> bool:
        """Determine if this resource should be hidden based on filtering options."""
        # We no longer need to treat READ operations differently
        # Just return False as we handle filtering elsewhere
        return False

def strip_ansi(text):
    """Remove ANSI escape sequences from text.

    Args:
        text: String to clean

    Returns:
        String with ANSI escape sequences removed
    """
    return re.sub(ANSI_ESCAPE_PATTERN, '', text)


def parse_resource_header(line: str) -> Optional[Resource]:
    """
    Parse a line to see if it's a resource operation header.
    Returns a Resource object if it is, None otherwise.

    Args:
        line: The line to parse (should be stripped of ANSI codes and whitespace)

    Returns:
        Resource object if the line is a resource header, None otherwise
    """
    # Check for different resource operation patterns
    # All formats follow the pattern: [symbol] resource_type: (operation)
    create_match = re.match(r'^\+\s+(.+?):\s*\(create\)$', line)
    read_match = re.match(r'^(?:>|\+|\-|~|\+-)\s+(.+?):\s*\(read\)$', line)  # Match any symbol for read
    update_match = re.match(r'^~\s+(.+?):\s*\(update\)$', line)
    delete_match = re.match(r'^-\s+(.+?):\s*\(delete\)$', line)
    replace_match = re.match(r'^\+-\s+(.+?):\s*\(replace\)$', line)

    # Handle create operations
    if create_match:
        resource_type = create_match.group(1)
        return Resource(operation=ResourceOp.CREATE, resource_type=resource_type)

    # Handle read operations
    elif read_match:
        resource_type = read_match.group(1)
        return Resource(operation=ResourceOp.READ, resource_type=resource_type)

    # Handle update operations
    elif update_match:
        resource_type = update_match.group(1)
        return Resource(operation=ResourceOp.UPDATE, resource_type=resource_type)

    # Handle delete operations
    elif delete_match:
        resource_type = delete_match.group(1)
        return Resource(operation=ResourceOp.DELETE, resource_type=resource_type)

    # Handle replace operations
    elif replace_match:
        resource_type = replace_match.group(1)
        return Resource(operation=ResourceOp.REPLACE, resource_type=resource_type)

    return None


def parse_metadata_line(line: str) -> Optional[MetadataEntry]:
    """
    Parse a line to extract metadata in the form [key=value] or similar.

    The line must match the exact pattern of [key=value] where:
    1. The line starts with '['
    2. Followed by a key that does not contain '=' or '['
    3. Followed by '='
    4. Followed by a value that does not contain ']'
    5. Ending with ']'

    Args:
        line: The line to parse (should be stripped of ANSI codes and whitespace)

    Returns:
        MetadataEntry if the line contains metadata, None otherwise
    """
    # Use a strict regex pattern to match only true metadata lines
    # This avoids matching array outputs or other content with brackets
    metadata_match = re.match(r'^\[([^=\[\]]+)=([^\]]+)\]$', line.strip())

    if metadata_match:
        key = metadata_match.group(1).strip()
        value = metadata_match.group(2).strip()
        return MetadataEntry(key=key, value=value, original_line=line)

    # Also check for key:value pattern as a backup
    metadata_match = re.match(r'^\[([^:\[\]]+):([^\]]+)\]$', line.strip())
    if metadata_match:
        key = metadata_match.group(1).strip()
        value = metadata_match.group(2).strip()
        return MetadataEntry(key=key, value=value, original_line=line)

    return None


def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Display Pulumi preview outputs from CircleCI jobs"
    )

    # Add a hidden quiet flag
    parser.add_argument(
        "--quiet",
        action="store_true",
        default=False,  # Default to non-quiet mode
        help=argparse.SUPPRESS  # Hide from help output
    )
    parser.add_argument(
        "job_or_url",
        help="CircleCI job ID or full URL"
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Show detailed diagnostic information during processing"
    )
    parser.add_argument(
        "--output-file",
        default="pulumi_preview_output.txt",
        help="File to save the raw output to (default: pulumi_preview_output.txt)"
    )
    parser.add_argument(
        "--hide-config",
        action="store_true",
        help="Redact configuration blocks that start with 'Loaded'"
    )
    parser.add_argument(
        "--hide-env",
        action="store_true",
        help="Hide environment-related lines: \"Read option env\", \"Environment Flag\", and all resource read operations including their metadata"
    )
    parser.add_argument(
        "--gh-org",
        default="DACH-NY",
        help="GitHub organization name (default: DACH-NY)"
    )
    parser.add_argument(
        "--gh-repo",
        default="canton-network-internal",
        help="GitHub repository name (default: canton-network-internal)"
    )
    parser.add_argument(
        "--hide-grafana",
        action="store_true",
        help="Hide Grafana dashboard changes (ConfigMaps with observability/cn-grafana in ID)"
    )
    parser.add_argument(
        "--circleci-token",
        default=None,
        help="CircleCI API token (default: CIRCLECI_TOKEN environment variable)"
    )

    # Resource operation filtering
    parser.add_argument(
        "--include-ops",
        help="Only show specified operations (comma-separated list: create,read,update,delete,replace)",
        type=str
    )
    parser.add_argument(
        "--exclude-ops",
        help="Hide specified operations (comma-separated list: create,read,update,delete,replace)",
        type=str
    )

    # Resource type filtering
    parser.add_argument(
        "--include-types",
        help="Only show resources matching specified types (comma-separated, supports wildcards)",
        type=str
    )
    parser.add_argument(
        "--exclude-types",
        help="Hide resources matching specified types (comma-separated, supports wildcards)",
        type=str
    )

    # Resource name filtering is not supported - only ID-based filtering is available

    # Resource ID filtering
    parser.add_argument(
        "--include-id-pattern",
        help="Only show resources with IDs matching patterns (comma-separated, supports wildcards)",
        type=str
    )
    parser.add_argument(
        "--exclude-id-pattern",
        help="Hide resources with IDs matching patterns (comma-separated, supports wildcards)",
        type=str
    )

    # Output options
    parser.add_argument(
        "--summary",
        action="store_true",
        help="Show only a summary of changes by resource type"
    )
    parser.add_argument(
        "--count-only",
        action="store_true",
        help="Show only the count of resources by operation type"
    )


    return parser.parse_args()


def get_circleci_token(args):
    """Get CircleCI API token from argument or environment variable."""
    token = args.circleci_token or os.environ.get("CIRCLECI_TOKEN")
    if not token:
        print(colored("Error: CIRCLECI_TOKEN not set via argument or environment variable", "red", attrs=["bold"]),
              file=sys.stderr)
        sys.exit(1)
    return token


def extract_job_id(job_or_url):
    """Extract job ID from the input string."""
    return job_or_url.split("/")[-1]


def get_output_urls(job_id, token, org, repo):
    """Get output URLs from CircleCI API."""
    api_url = f"https://circleci.com/api/v1.1/project/gh/{org}/{repo}/{job_id}"
    headers = {"Circle-Token": token}

    try:
        response = requests.get(api_url, headers=headers)
        response.raise_for_status()

        output_urls = set()
        for step in response.json().get("steps", []):
            for action in step.get("actions", []):
                if "output_url" in action:
                    output_urls.add(action["output_url"])

        return sorted(output_urls)

    except requests.exceptions.RequestException as e:
        print(colored(f"Error: Failed to get job info: {e}", "red"), file=sys.stderr)
        sys.exit(1)


def redact_url(url):
    """Redact sensitive tokens from URLs by cutting off everything after token= and replacing with <REDACTED_TOKEN>."""
    if "token=" in url:
        base_url = url.split("token=")[0] + "token="
        return base_url + "<REDACTED_TOKEN>"
    return url


def fetch_and_save_output(url, output_file, verbose=False, quiet=False):
    """Fetch output from CircleCI and save it to a file."""
    try:
        if verbose:
            print(colored(f"Fetching output from {url}", "cyan"))

        resp = requests.get(url)
        resp.raise_for_status()

        # Extract messages from the output
        data = resp.json()

        if verbose:
            print(colored(f"Retrieved {len(data)} message items from CircleCI output", "cyan"))

        pulumi_content = []
        found_pulumi_command = False

        # Extract and combine message content
        for item in data:
            if "message" in item:
                message = item["message"]

                # Look for 'Running Pulumi Command' marker
                if "Running Pulumi Command" in message:
                    found_pulumi_command = True
                    # Only include content after 'Running Pulumi Command'
                    message = message.split("Running Pulumi Command", 1)[1]

                # Only add message to output if we've found the Pulumi command marker
                if found_pulumi_command:
                    pulumi_content.append(message)

        # Save combined Pulumi output to file
        if pulumi_content:
            combined_output = "\n".join(pulumi_content)

            # Check if we're appending to an existing file
            write_mode = 'a' if os.path.exists(output_file) else 'w'
            with open(output_file, write_mode) as f:
                f.write(f"\n--- Output from {redact_url(url)} ---\n\n")
                f.write(combined_output)
                f.write("\n\n")

            return True
        else:
            if verbose:
                print(colored("No Pulumi command output found in this output", "yellow"))
            return False

    except requests.exceptions.RequestException as e:
        print(colored(f"Error fetching output: {e}", "red"))
        return False
    except json.JSONDecodeError:
        print(colored(f"Error: Invalid JSON response from {url}", "red"))
        return False


def is_environment_line(line):
    """
    Check if a line is related to environment settings.

    Args:
        line: The line to check (should be stripped of ANSI codes)

    Returns:
        True if the line is environment-related, False otherwise
    """
    return "Environment Flag" in line or "Read option env" in line


def parse_comma_separated_patterns(pattern_str):
    """
    Parse a comma-separated string of patterns.

    Args:
        pattern_str: Comma-separated string of patterns

    Returns:
        List of patterns, or empty list if pattern_str is None
    """
    if not pattern_str:
        return []

    return [p.strip() for p in pattern_str.split(',') if p.strip()]


def matches_pattern(value, patterns):
    """
    Check if a value matches any of the given patterns.
    Patterns can include wildcards (* and ?) as well as regex-style patterns.
    Supports patterns like '.*validator.*' to match substrings anywhere in the value.

    Args:
        value: String to match against patterns
        patterns: List of patterns that can include wildcards or regex patterns

    Returns:
        True if the value matches any pattern, False otherwise
    """
    if not value or not patterns:
        return False

    for pattern in patterns:
        # For patterns containing .* use regex directly without escaping dots
        # This allows patterns like '.*validator.*' to work as expected
        if '.*' in pattern:
            # For .*-containing patterns, compile the regex directly
            try:
                if re.search(pattern, value):
                    return True
            except re.error:
                # If the pattern isn't valid regex, fall back to standard glob matching
                regex_pattern = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                if re.match(f"^{regex_pattern}$", value):
                    return True
        else:
            # Standard glob pattern conversion
            regex_pattern = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
            if re.match(f"^{regex_pattern}$", value):
                return True

    return False


def resource_matches_filter(resource, filter_args):
    """
    Check if a resource matches the given filter arguments.

    Args:
        resource: Resource object to check
        filter_args: Filter arguments from command line

    Returns:
        True if the resource should be included in output, False if it should be filtered out
    """
    # If no filter arguments provided, include everything
    if filter_args is None:
        return True

    verbose = hasattr(filter_args, 'verbose') and filter_args.verbose

    # Filter by operation type
    if hasattr(filter_args, 'include_ops') and filter_args.include_ops:
        op_types = parse_comma_separated_patterns(filter_args.include_ops)
        if op_types and resource.operation.name.lower() not in [op.lower() for op in op_types]:
            return False

    if hasattr(filter_args, 'exclude_ops') and filter_args.exclude_ops:
        op_types = parse_comma_separated_patterns(filter_args.exclude_ops)
        if op_types and resource.operation.name.lower() in [op.lower() for op in op_types]:
            return False

    # Filter by resource type
    if hasattr(filter_args, 'include_types') and filter_args.include_types:
        type_patterns = parse_comma_separated_patterns(filter_args.include_types)
        if type_patterns and not matches_pattern(resource.resource_type, type_patterns):
            return False

    if hasattr(filter_args, 'exclude_types') and filter_args.exclude_types:
        type_patterns = parse_comma_separated_patterns(filter_args.exclude_types)
        if type_patterns and matches_pattern(resource.resource_type, type_patterns):
            return False

    # Only ID-based filtering is supported

    # Filter by ID pattern (checks metadata for id=value)
    resource_id = None

    if verbose:
        print(colored(f"Checking resource: {resource.resource_type} ({resource.operation.name})", "blue"))

    # First try to find the ID in metadata
    for meta in resource.metadata:
        if meta.key == "id":
            resource_id = meta.value
            if verbose:
                print(colored(f"  Found ID in metadata: {resource_id}", "blue"))
            break

    if verbose and not resource_id:
        print(colored("  No ID found for this resource", "yellow"))
        # Print all metadata to help with debugging
        print(colored("  Available metadata:", "blue"))
        for meta in resource.metadata:
            print(colored(f"    {meta.key}={meta.value}", "blue"))

    # If ID-based filtering is requested, exclude resources without IDs
    if hasattr(filter_args, 'include_id_pattern') and filter_args.include_id_pattern and not resource_id:
        if verbose:
            print(colored("  No ID found - excluding from ID filter results", "yellow"))
        return False

    if resource_id:
        if hasattr(filter_args, 'include_id_pattern') and filter_args.include_id_pattern:
            id_patterns = parse_comma_separated_patterns(filter_args.include_id_pattern)
            if id_patterns and not matches_pattern(resource_id, id_patterns):
                if verbose:
                    print(colored(f"  ID '{resource_id}' doesn't match include patterns '{filter_args.include_id_pattern}'", "yellow"))
                return False

        if hasattr(filter_args, 'exclude_id_pattern') and filter_args.exclude_id_pattern:
            id_patterns = parse_comma_separated_patterns(filter_args.exclude_id_pattern)
            if id_patterns and matches_pattern(resource_id, id_patterns):
                if verbose:
                    print(colored(f"  ID '{resource_id}' matches exclude patterns '{filter_args.exclude_id_pattern}'", "yellow"))
                return False

    # If we reached here, the resource passes all filters
    return True
def pre_filter_line(line, hide_env=False):
    """
    Pre-filter a line based on simple pattern matching.
    Only filters environment lines directly.
    Resource filtering is now handled by the structured parsing logic.

    Args:
        line: The line to check
        hide_env: If True, filter out lines containing "Read option env" or "Environment Flag"

    Returns:
        True if the line should be kept, False if it should be filtered out
    """
    # Handle empty lines with color codes by checking if strip removes all content
    if not strip_ansi(line).strip():
        return True  # Keep empty lines for now, they'll be handled later

    # Check if it's an environment flag or read option line
    if hide_env:
        # Remove ANSI color codes for more reliable detection
        stripped = strip_ansi(line)
        # Only filter environment flag lines here
        if is_environment_line(stripped):
            return False

    # Keep all other lines - resource filtering happens in the structured parsing
    return True


def clean_ansi_empty_line(line):
    """
    Check if a line is effectively empty after removing ANSI color codes.
    Args:
        line: The line to check
    Returns:
        True if the line is empty after removing color codes, False otherwise
    """
    # Remove ANSI color codes and check if anything remains
    return not bool(strip_ansi(line).strip())


def parse_resources(lines, verbose=False):
    """
    Parse all resources from the Pulumi output lines.

    This completely rewritten function ensures proper resource boundaries detection
    and tracks all lines associated with each resource.

    Args:
        lines: List of output lines
        verbose: Whether to print verbose information

    Returns:
        List of Resource objects
    """
    resources = []
    i = 0

    # First pass - identify all resource headers
    while i < len(lines):
        line = lines[i]
        stripped = strip_ansi(line).strip()

        # Skip empty lines
        if not stripped:
            i += 1
            continue

        # Check for resource header
        resource = parse_resource_header(stripped)
        if resource:
            if verbose:
                print(colored(f"Found resource header: {resource.operation.name} on {resource.resource_type}", "blue"))

            # Initialize the resource
            resource.header_line = line
            resource.header_line_idx = i
            resource.all_lines = [i]  # Start with the header line
            resources.append(resource)

        i += 1

    # Second pass - assign lines to resources
    for idx, resource in enumerate(resources):
        header_idx = resource.header_line_idx

        # Determine where this resource ends (either at the next resource header or some heuristic)
        end_idx = len(lines)
        if idx < len(resources) - 1:
            # End at the line before the next resource header
            end_idx = resources[idx + 1].header_line_idx

        # Now go through lines from header to end, collecting metadata and resource lines
        i = header_idx + 1
        while i < end_idx:
            line = lines[i]
            stripped = strip_ansi(line).strip()

            # Skip empty lines but include them in the resource's all_lines
            if not stripped:
                resource.all_lines.append(i)
                i += 1
                continue

            # Check if this is metadata
            metadata_entry = parse_metadata_line(stripped)
            if metadata_entry:
                resource.metadata.append(metadata_entry)
                resource.metadata_lines.append((i, line))
                if verbose and metadata_entry.key == "id":
                    print(colored(f"  Found ID: {metadata_entry.value}", "blue"))
            else:
                # This is content (version changes, property changes, etc.)
                resource.content_lines.append((i, line))

            # Add this line to the resource's tracked lines
            resource.all_lines.append(i)
            i += 1


    return resources
def parse_and_print_output(output_file, hide_config=False, hide_env=False,
                          verbose=False, hide_grafana=False, filter_args=None,
                          summary_only=False, count_only=False):
    """Parse the saved output file and print it with formatting.

    Args:
        output_file: Path to the output file to parse
        hide_config: If True, hide configuration blocks that start with 'Loaded'
        hide_env: If True, hide lines related to environment and resource reads
        verbose: If True, show additional diagnostic information
        hide_grafana: If True, hide Grafana dashboard changes
        filter_args: Arguments for filtering resources by type, operation, id, etc.
        summary_only: If True, show only summary of changes
        count_only: If True, show only count of resources by operation type
    """
    try:
        if verbose:
            print(colored(f"Parsing output from {output_file}", "cyan"))

        with open(output_file, 'r') as f:
            content = f.read()

        # Process and print the content
        all_lines = content.split('\n')

        # First pass: filter out lines with only ANSI color codes and no actual content
        filtered_lines = []
        for line in all_lines:
            if not clean_ansi_empty_line(line):
                filtered_lines.append(line)
        if verbose and len(filtered_lines) < len(all_lines):
            print(colored(f"Filtered out {len(all_lines) - len(filtered_lines)} empty ANSI lines", "blue"))

        all_lines = filtered_lines

        # Parse resources structurally
        resources = parse_resources(all_lines, verbose)
        if verbose:
            print(colored(f"Found {len(resources)} resources in the output", "blue"))

        # Process resources based on filters
        # Track resources by operation for summary
        resources_by_op = defaultdict(int)
        filtered_resources = []

        # Create a set of line indices to hide
        lines_to_hide = set()

        for resource in resources:
            # Count by operation for summary
            resources_by_op[resource.operation.name] += 1

            # Check if this resource should be filtered out based on the filter arguments
            should_be_hidden = filter_args and not resource.should_display(filter_args)

            if should_be_hidden:
                # Add ALL lines belonging to this resource to the hide set
                for line_idx in resource.all_lines:
                    lines_to_hide.add(line_idx)
            else:
                filtered_resources.append(resource)

        # Handle summary or count only modes
        if summary_only or count_only:
            op_colors = {
                ResourceOp.CREATE.name: "green",
                ResourceOp.UPDATE.name: "yellow",
                ResourceOp.DELETE.name: "red",
                ResourceOp.REPLACE.name: "magenta",
                ResourceOp.READ.name: "blue",
                ResourceOp.UNKNOWN.name: "white"
            }

            if count_only:
                # Just print the counts by operation
                print(colored("Resource count by operation:", "cyan", attrs=["bold"]))
                for op, count in sorted(resources_by_op.items()):
                    color = op_colors.get(op, "white")
                    print(colored(f"{op}: ", color) + str(count))

                total = sum(resources_by_op.values())
                print(colored(f"Total: {total}", "cyan", attrs=["bold"]))
                return

            if summary_only:
                # Print summary with resource types
                print(colored("Resource summary by operation:", "cyan", attrs=["bold"]))

                # Group resources by operation and type
                by_op_and_type = defaultdict(lambda: defaultdict(list))
                for res in filtered_resources:
                    by_op_and_type[res.operation.name][res.resource_type].append(res)

                # Print summary by operation and type
                for op in sorted(by_op_and_type.keys()):
                    op_color = op_colors.get(op, "white")
                    type_count = len(by_op_and_type[op])
                    resource_count = sum(len(resources) for resources in by_op_and_type[op].values())

                    print(colored(f"{op}: ", op_color, attrs=["bold"]) +
                          f"{resource_count} resources of {type_count} types")

                    # Print resource types
                    for res_type, res_list in sorted(by_op_and_type[op].items()):
                        print(colored(f"  - {res_type}: ", op_color) + str(len(res_list)))

                print(colored(f"Total filtered resources: {len(filtered_resources)}", "cyan", attrs=["bold"]))
                return



        # Second pass: pre-filter environment lines directly
        # Resource lines will be filtered based on the lines_to_hide set
        filtered_lines = []
        for i, line in enumerate(all_lines):
            if i in lines_to_hide:
                continue  # Skip lines that should be hidden

            if pre_filter_line(line, hide_env):
                filtered_lines.append(line)

        if verbose and len(filtered_lines) < len(all_lines):
            print(colored(f"Filtered out {len(all_lines) - len(filtered_lines)} lines", "blue"))

        lines = filtered_lines

        # State variables for tracking config blocks and sections
        in_config_block = False
        in_grafana_block = False  # Track if we're in a grafana change block
        brace_count = 0
        reached_resources = False
        last_line_was_hidden = False  # Track any hidden line, not just hideed configs
        grafana_indent_level = 0  # Track the base indentation level of the grafana block

        i = 0
        while i < len(lines):
            line = lines[i]

            # Skip empty lines after any hidden content to avoid excessive spacing
            if last_line_was_hidden and not line.strip():
                last_line_was_hidden = True  # Keep the hidden state for consecutive empty lines
                i += 1
                continue

            # Reset hidden line tracker by default
            last_line_was_hidden = False

            # Check if we've reached the Resources section which is the end of a Pulumi command
            # Strip ANSI escape codes before checking for Resources text
            stripped_content = strip_ansi(line).strip()
            if stripped_content == "Resources:" or stripped_content.startswith("Resources:"):
                reached_resources = True
                in_grafana_block = False  # Exit grafana block if we were in one
                print(line)  # Print the Resources line with original formatting
                i += 1
                continue
            # Check for grafana ConfigMaps if hide_grafana is enabled
            if hide_grafana and not in_grafana_block and "kubernetes:core/v1:ConfigMap:" in line.strip():
                # Look ahead to see if the next line contains a grafana ID
                next_line_idx = i + 1
                found_grafana = False
                metadata_end_idx = next_line_idx

                # Look through the next few lines for grafana identifiers
                for j in range(next_line_idx, min(next_line_idx + 10, len(lines))):
                    stripped_j = strip_ansi(lines[j])
                    if not stripped_j.strip():
                        continue
                    if "observability/cn-grafana" in stripped_j or "grafana-dashboards" in stripped_j or "grafana-alerting" in stripped_j:
                        found_grafana = True
                        metadata_end_idx = j
                    # If we find a line that starts a new resource, stop looking
                    if (stripped_j.strip().startswith("+") or stripped_j.strip().startswith("~") or
                        stripped_j.strip().startswith("-")) and ":" in stripped_j and not "data:" in stripped_j:
                        break

                if found_grafana:
                    # This is a grafana dashboard change, skip until we find a non-grafana change
                    in_grafana_block = True

                    # Calculate the indentation level to know when we exit this resource block
                    # Use the main resource line's indentation, not the metadata lines
                    stripped_line = strip_ansi(line)
                    indent_match = re.match(r'^(\s*)', stripped_line)
                    if indent_match:
                        grafana_indent_level = len(indent_match.group(1))
                    else:
                        grafana_indent_level = 0

                    # Look ahead for data block to handle the case where it's indented differently
                    for j in range(metadata_end_idx + 1, min(metadata_end_idx + 5, len(lines))):
                        stripped_j = strip_ansi(lines[j])
                        if "data:" in stripped_j:
                            # Use the indentation of the parent element for tracking
                            data_indent_match = re.match(r'^(\s*)', stripped_j)
                            if data_indent_match:
                                grafana_indent_level = len(data_indent_match.group(1)) - 2  # Adjust to parent level
                                break

                    if verbose:
                        print(colored(f"Skipping grafana dashboard change, base indent level: {grafana_indent_level}", "blue"))

                    last_line_was_hidden = True
                    i += 1
                    continue

            # If we're in a grafana block, skip all content until we find a new resource at the same or higher level
            if hide_grafana and in_grafana_block:
                # Check the indentation of this line to see if we've exited the grafana block
                stripped_line = strip_ansi(line)
                indent_match = re.match(r'^(\s*)', stripped_line)
                current_indent = len(indent_match.group(1)) if indent_match else 0

                # Exit conditions for grafana block:
                # 1. We hit the Resources section
                # 2. We've returned to the same or higher level of indentation with a new resource marker (+ or ~ or -)
                stripped_content = strip_ansi(line).strip()
                if stripped_content == "Resources:" or stripped_content.startswith("Resources:"):
                    in_grafana_block = False  # Exit grafana block
                elif (current_indent <= grafana_indent_level and
                     (stripped_content.startswith("+") or stripped_content.startswith("~") or stripped_content.startswith("-")) and
                     ((":" in stripped_content and "(" in stripped_content and ")" in stripped_content))):

                    # Check if this new resource is also a grafana dashboard that should be ignored
                    # If it is, don't exit the block - we'll process it in the next iteration
                    is_another_grafana = False

                    # Check if this is a ConfigMap replacement that might be a grafana dashboard
                    if "kubernetes:core/v1:ConfigMap:" in stripped_content:
                        # Look ahead to check if it has grafana identifiers
                        next_line_idx = i + 1
                        while next_line_idx < len(lines) and not lines[next_line_idx].strip():
                            next_line_idx += 1

                        # Check the next few non-empty lines for grafana identifiers
                        for j in range(next_line_idx, min(next_line_idx + 5, len(lines))):
                            if j < len(lines):
                                stripped_j = strip_ansi(lines[j])
                                if stripped_j.strip():
                                    if "observability/cn-grafana" in stripped_j or "grafana-dashboards" in stripped_j:
                                        is_another_grafana = True
                                        if verbose:
                                            print(colored("Found another grafana dashboard, continuing to skip", "blue"))
                                        break

                    # Only exit grafana block if this is NOT another grafana dashboard
                    if not is_another_grafana:
                        in_grafana_block = False
                        if verbose:
                            print(colored(f"Found new resource at indent level {current_indent}, exiting grafana block", "blue"))

                # If still in grafana block, skip this line
                if in_grafana_block:
                    last_line_was_hidden = True
                    i += 1
                    continue

            # Check for configuration sections if hiding is enabled
            stripped_line = strip_ansi(line)
            if hide_config and "Loaded" in stripped_line and "{" in stripped_line.split("Loaded", 1)[1]:
                # Extract the prefix of what's being loaded for a more informative message
                loaded_text = stripped_line.split("Loaded", 1)[1].split("{", 1)[0].strip()
                print(colored(f"Loaded {loaded_text} REDACTED", "blue"))
                in_config_block = True
                brace_count = stripped_line.count('{') - stripped_line.count('}')
                last_line_was_hidden = True
                i += 1
                continue

            # Count braces if we're in a config block that needs hiding
            if hide_config and in_config_block:
                stripped_line = strip_ansi(line)
                brace_count += stripped_line.count('{') - stripped_line.count('}')
                if brace_count <= 0:
                    in_config_block = False
                last_line_was_hidden = True
                i += 1
                continue

            # Print line with original coloring
            stripped = strip_ansi(line).strip()
            if stripped:  # Only print non-empty lines
                # Check if this is a resource header and apply nice formatting if desired
                resource_header = parse_resource_header(stripped)
                if resource_header:
                    # Just print the original line with its coloring
                    print(line)
                else:
                    # Print regular line or metadata
                    print(line)
            elif not last_line_was_hidden:  # Print empty lines only if not following hidden content
                print(line)

            # Stop after Resources section if we see a line with repeated "="
            if reached_resources and "=" * 10 in line:
                if verbose:
                    print(colored("Found end of resources section, stopping output", "cyan"))
                break

            i += 1

        return True

    except FileNotFoundError:
        print(colored(f"Error: Output file {output_file} not found", "red"))
        return False
    except Exception as e:
        print(colored(f"Error parsing output file: {e}", "red"))
        return False


def main():
    args = parse_args()

    # Check if help was requested - handle differently
    if len(sys.argv) > 1 and sys.argv[1] in ['-h', '--help']:
        return

    token = get_circleci_token(args)
    job_id = extract_job_id(args.job_or_url)
    output_file = args.output_file

    if not args.quiet:
        print(colored(f"Fetching preview for CircleCI job {job_id}...", "cyan"))

    output_urls = get_output_urls(job_id, token, args.gh_org, args.gh_repo)
    if not output_urls:
        print(colored("No output URLs found for this job", "yellow"))
        return

    if not args.quiet:
        print(colored(f"Found {len(output_urls)} output sources to check", "cyan"))

    # Make sure we're starting with a fresh output file
    if os.path.exists(output_file):
        os.remove(output_file)

    # First stage: save all output to the file
    found_any = False
    for i, url in enumerate(output_urls):
        if args.verbose and not args.quiet:
            print(colored(f"\nSaving output from source {i + 1}/{len(output_urls)}", "cyan"))

        if fetch_and_save_output(url, output_file, args.verbose, args.quiet):
            found_any = True

    if not found_any:
        print(colored("\nNo Pulumi preview content found in any output.", "yellow"))
        return

    # Second stage: parse the file and print formatted output
    if not args.quiet:
        print(colored(f"\nSaved raw output to {output_file}", "cyan"))
        print(colored("Now displaying parsed output:", "cyan"))
        print(colored("=" * 80, "blue"))

    # Parse and display the output
    parse_and_print_output(
        output_file,
        hide_config=args.hide_config,
        hide_env=args.hide_env,
        verbose=args.verbose,
        hide_grafana=args.hide_grafana,
        filter_args=args,
        summary_only=args.summary,
        count_only=args.count_only
    )

    if not args.quiet:
        print(colored("=" * 80, "blue"))
        print(colored(f"Finished processing. Raw output saved to {output_file}", "cyan"))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        import traceback
        print(f"Error: {e}")
        traceback.print_exc()
        sys.exit(1)
