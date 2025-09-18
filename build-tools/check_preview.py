#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Script to display Pulumi preview outputs from CircleCI jobs or local files in a readable format.

Usage:
  ./check_preview.py JOB_ID_OR_FILE [--verbose] [--output-file OUTPUT_FILE] [--hide-config]
                                    [--hide-env] [--gh-org GH_ORG] [--gh-repo GH_REPO]
                                    [--include-ops OPERATIONS] [--exclude-ops OPERATIONS]
                                    [--include-types RESOURCE_TYPES] [--exclude-types RESOURCE_TYPES]
                                    [--include-id-pattern PATTERNS] [--exclude-id-pattern PATTERNS]
                                    [--summary] [--count-only]

Arguments:
  JOB_ID_OR_FILE       CircleCI job ID, full URL, or a local file path
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
from typing import List, Dict, Optional, Tuple, Set, Any, Callable, TypeVar, Union

# Pattern to match all ANSI escape sequences
ANSI_ESCAPE_PATTERN = r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])'

#
# Data Classes (immutable data records)
#

# Resource operation types
class ResourceOp(Enum):
    CREATE = auto()
    READ = auto()
    UPDATE = auto()
    DELETE = auto()
    REPLACE = auto()
    UNKNOWN = auto()

@dataclass(frozen=True)
class FilterArgs:
    """Immutable record for filter arguments"""
    hide_config: bool = False
    hide_env: bool = False
    hide_grafana: bool = False
    clean_output: bool = False
    include_ops: Optional[str] = None
    exclude_ops: Optional[str] = None
    include_types: Optional[str] = None
    exclude_types: Optional[str] = None
    include_id_pattern: Optional[str] = None
    exclude_id_pattern: Optional[str] = None
    summary: bool = False
    count_only: bool = False
    verbose: bool = False
    quiet: bool = False

@dataclass(frozen=True)
class MetadataEntry:
    """Immutable record for a metadata entry"""
    key: str
    value: str
    original_line: str

@dataclass
class ContentLine:
    """Basic content line information"""
    line_idx: int  # Line number in the original file
    content: str   # Original content with ANSI codes
    stripped: str = None  # Content with ANSI codes stripped

    def __post_init__(self):
        if self.stripped is None:
            self.stripped = strip_ansi(self.content).strip()

# Content types (records containing just data)
@dataclass
class ResourceContent:
    """Resource content with metadata and content lines"""
    line_idx: int
    content: str
    resource_type: str
    operation: ResourceOp
    metadata: List[MetadataEntry] = field(default_factory=list)
    metadata_lines: List[ContentLine] = field(default_factory=list)
    content_lines: List[ContentLine] = field(default_factory=list)

    def get_line_indices(self) -> List[int]:
        """Get all line indices this resource covers"""
        indices = [self.line_idx]
        indices.extend(line.line_idx for line in self.metadata_lines)
        indices.extend(line.line_idx for line in self.content_lines)
        return indices

@dataclass
class ConfigContent:
    """Configuration content"""
    line_idx: int
    content: str
    is_header: bool
    brace_count: int

@dataclass
class EnvironmentContent:
    """Environment variable or flag"""
    line_idx: int
    content: str

@dataclass
class HeaderContent:
    """Section header like 'Resources:'"""
    line_idx: int
    content: str
    header_type: str

@dataclass
class EmptyContent:
    """Empty line"""
    line_idx: int
    content: str

@dataclass
class OtherContent:
    """Any other content"""
    line_idx: int
    content: str
    is_delimiter: bool = False

# Union type for all content
ContentType = Union[
    ResourceContent,
    ConfigContent,
    EnvironmentContent,
    HeaderContent,
    EmptyContent,
    OtherContent
]

@dataclass
class ParsedOutput:
    """Container for all parsed content"""
    resources: List[ResourceContent] = field(default_factory=list)
    configs: List[ConfigContent] = field(default_factory=list)
    environments: List[EnvironmentContent] = field(default_factory=list)
    headers: List[HeaderContent] = field(default_factory=list)
    empty_lines: List[EmptyContent] = field(default_factory=list)
    other_content: List[OtherContent] = field(default_factory=list)

    def all_content(self) -> List[ContentType]:
        """Get all content in order of line_idx"""
        all_items: List[ContentType] = []
        all_items.extend(self.resources)
        all_items.extend(self.configs)
        all_items.extend(self.environments)
        all_items.extend(self.headers)
        all_items.extend(self.empty_lines)
        all_items.extend(self.other_content)
        return sorted(all_items, key=lambda x: x.line_idx)

#
# Parsing Functions
#

def strip_ansi(text: str) -> str:
    """Remove ANSI escape sequences from text."""
    return re.sub(ANSI_ESCAPE_PATTERN, '', text)

def clean_ansi_empty_line(line: str) -> bool:
    """Check if a line is effectively empty after removing ANSI color codes."""
    return not bool(strip_ansi(line).strip())

def parse_comma_separated_patterns(pattern_str: str) -> List[str]:
    """Parse a comma-separated string of patterns."""
    if not pattern_str:
        return []
    return [p.strip() for p in pattern_str.split(',') if p.strip()]

def matches_pattern(value: str, patterns: List[str]) -> bool:
    """Check if a value matches any of the given patterns."""
    if not value or not patterns:
        return False

    for pattern in patterns:
        # For patterns containing .* use regex directly without escaping dots
        if '.*' in pattern:
            try:
                if re.search(pattern, value):
                    return True
            except re.error:
                regex_pattern = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                if re.match(f"^{regex_pattern}$", value):
                    return True
        else:
            # Standard glob pattern conversion
            regex_pattern = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".")
            if re.match(f"^{regex_pattern}$", value):
                return True
    return False

def parse_resource_header(line: str) -> Optional[Tuple[str, ResourceOp]]:
    """Parse a resource header from a line."""
    # Basic checks before regex - improve performance
    first_char = line[0] if line else ''
    if first_char not in ['+', '-', '~', '>', ' ']:
        return None

    # If line doesn't contain operation markers, skip
    if '(create)' not in line and '(update)' not in line and '(read)' not in line and \
       '(delete)' not in line and '(replace)' not in line:
        return None

    # Extremely simplified parsing based on operation markers
    if '(create)' in line and line.strip().startswith('+'):
        resource_type = line.split('(create)')[0].strip()
        if resource_type.endswith(':'):
            resource_type = resource_type[:-1].strip()
        if resource_type.startswith('+ '):
            resource_type = resource_type[2:].strip()
        return (resource_type, ResourceOp.CREATE)

    elif '(read)' in line and line.strip().startswith('>'):
        resource_type = line.split('(read)')[0].strip()
        if resource_type.endswith(':'):
            resource_type = resource_type[:-1].strip()
        if resource_type.startswith('> '):
            resource_type = resource_type[2:].strip()
        return (resource_type, ResourceOp.READ)

    elif '(update)' in line and line.strip().startswith('~'):
        resource_type = line.split('(update)')[0].strip()
        if resource_type.endswith(':'):
            resource_type = resource_type[:-1].strip()
        if resource_type.startswith('~ '):
            resource_type = resource_type[2:].strip()
        return (resource_type, ResourceOp.UPDATE)

    elif '(delete)' in line and line.strip().startswith('-'):
        resource_type = line.split('(delete)')[0].strip()
        if resource_type.endswith(':'):
            resource_type = resource_type[:-1].strip()
        if resource_type.startswith('- '):
            resource_type = resource_type[2:].strip()
        return (resource_type, ResourceOp.DELETE)

    elif '(replace)' in line and line.strip().startswith('+-'):
        resource_type = line.split('(replace)')[0].strip()
        if resource_type.endswith(':'):
            resource_type = resource_type[:-1].strip()
        if resource_type.startswith('+- '):
            resource_type = resource_type[3:].strip()
        return (resource_type, ResourceOp.REPLACE)

    return None

def parse_metadata_line(line: str) -> Optional[MetadataEntry]:
    """Parse metadata from a line."""
    metadata_match = re.match(r'^\[([^=\[\]]+)=([^\]]+)\]$', line.strip())

    if metadata_match:
        key = metadata_match.group(1).strip()
        value = metadata_match.group(2).strip()
        return MetadataEntry(key=key, value=value, original_line=line)

    metadata_match = re.match(r'^\[([^:\[\]]+):([^\]]+)\]$', line.strip())
    if metadata_match:
        key = metadata_match.group(1).strip()
        value = metadata_match.group(2).strip()
        return MetadataEntry(key=key, value=value, original_line=line)

    return None

def is_environment_line(line: str) -> bool:
    """Check if a line is environment-related."""
    env_patterns = [
        "Environment Flag",
        "Read option env",
    ]

    for pattern in env_patterns:
        if pattern in line:
            return True
    return False

def is_config_header(line: str) -> bool:
    """Check if a line is a configuration header."""
    return "Loaded" in line and "{" in line.split("Loaded", 1)[1]

def is_section_header(line: str) -> bool:
    """Check if a line is a section header."""
    header_types = ["resources", "summary", "warning", "error"]
    content_lower = line.lower()
    return (line == "Resources:" or
            line.startswith("Resources:") or
            any(f"{header}:" in content_lower for header in header_types))

def get_header_type(line: str) -> str:
    """Get the type of section header."""
    header_types = ["resources", "summary", "warning", "error"]
    content_lower = line.lower()
    for header_type in header_types:
        if f"{header_type}:" in content_lower:
            return header_type
    return "unknown"

def categorize_line(idx: int, line: str) -> ContentType:
    """Categorize a line of content into its appropriate type."""
    stripped = strip_ansi(line).strip()

    # Skip empty lines
    if not stripped:
        return EmptyContent(idx, line)

    # Check if it's a resource header
    resource_header = parse_resource_header(stripped)
    if resource_header:
        return ResourceContent(idx, line, resource_header[0], resource_header[1])

    # Check if it's a delimiter line (like "--- Output from...")
    if stripped.startswith("--- Output from") and stripped.endswith("---"):
        # These are delimiter lines that separate different output sections
        # They should be treated as OtherContent with a special flag
        return OtherContent(idx, line, is_delimiter=True)

    # Check if it's an environment line
    if is_environment_line(stripped):
        return EnvironmentContent(idx, line)

    # Check if it's a configuration header
    if is_config_header(stripped):
        brace_count = stripped.count('{') - stripped.count('}')
        return ConfigContent(idx, line, True, brace_count)

    # Check if it's part of a configuration block (non-header)
    # We'll identify this when parsing the content as a whole

    # Check if it's a section header (like "Resources:")
    if is_section_header(stripped):
        return HeaderContent(idx, line, get_header_type(stripped))

    # Default to other content
    return OtherContent(idx, line)

def parse_content_lines(lines: List[str]) -> ParsedOutput:
    """Parse raw lines into structured content."""
    result = ParsedOutput()

    # First pass - categorize lines and identify resources
    content_by_idx = {}
    resource_indices = []
    resource_by_idx = {}

    # Track configuration blocks
    in_config_block = False
    current_brace_count = 0

    for i, line in enumerate(lines):
        # Skip lines that are just ANSI codes with no content
        if clean_ansi_empty_line(line):
            content = EmptyContent(i, line)
            result.empty_lines.append(content)
            content_by_idx[i] = content
            continue

        # Basic categorization first
        content = categorize_line(i, line)

        # Check if this is a config header to start tracking a new config block
        stripped = strip_ansi(line).strip()

        if isinstance(content, ConfigContent) and content.is_header:
            in_config_block = True
            current_brace_count = content.brace_count
            content_by_idx[i] = content
            result.configs.append(content)
            continue

        # Handle continued config block content
        if in_config_block:
            # Count braces to track nesting
            current_brace_count += stripped.count('{') - stripped.count('}')

            # This is part of a config block, mark it as such
            config_content = ConfigContent(i, line, False, current_brace_count)
            content_by_idx[i] = config_content
            result.configs.append(config_content)

            # If we've closed all braces, we're out of the config block
            if current_brace_count <= 0:
                in_config_block = False

            continue

        # Process non-config content
        if isinstance(content, ResourceContent):
            resource_indices.append(i)
            resource_by_idx[i] = content
            result.resources.append(content)
        elif isinstance(content, EnvironmentContent):
            result.environments.append(content)
        elif isinstance(content, HeaderContent):
            result.headers.append(content)
        elif isinstance(content, EmptyContent):
            result.empty_lines.append(content)
        else:
            result.other_content.append(content)

        content_by_idx[i] = content

    # Second pass - associate metadata and content with resources
    for idx, resource_idx in enumerate(resource_indices):
        resource = resource_by_idx[resource_idx]

        # Determine where this resource ends
        end_idx = len(lines)
        if idx < len(resource_indices) - 1:
            end_idx = resource_indices[idx + 1]

        # Process lines from after the resource header to the end of the resource
        i = resource_idx + 1
        resource_ended = False

        while i < end_idx and not resource_ended:
            if i in content_by_idx:
                content = content_by_idx[i]

                # Check if we've hit a delimiter line - that means the resource content has ended
                if isinstance(content, OtherContent) and getattr(content, 'is_delimiter', False):
                    resource_ended = True
                    continue

                if isinstance(content, EmptyContent):
                    # Skip empty lines but keep track of them
                    pass
                elif isinstance(content, OtherContent):
                    # Check if it's metadata for the resource
                    line_content = strip_ansi(content.content).strip()
                    metadata_entry = parse_metadata_line(line_content)
                    if metadata_entry:
                        resource.metadata.append(metadata_entry)
                        resource.metadata_lines.append(ContentLine(i, content.content, line_content))
                    else:
                        # It's content for the resource
                        resource.content_lines.append(ContentLine(i, content.content, line_content))
            i += 1

    return result

#
# Filter Functions
#

def is_grafana_resource(resource: ResourceContent) -> bool:
    """Check if a resource is Grafana-related."""
    # Check if this is a ConfigMap resource
    if "kubernetes:core/v1:ConfigMap" not in resource.resource_type:
        return False

    # Check if any metadata contains grafana identifiers
    for meta in resource.metadata:
        if meta.key == "id":
            id_value = meta.value
            if ("observability/cn-grafana" in id_value or
                "grafana-dashboards" in id_value or
                "grafana-alerting" in id_value):
                return True

    # Check content lines for grafana identifiers
    for content_line in resource.content_lines:
        if ("observability/cn-grafana" in content_line.stripped or
            "grafana-dashboards" in content_line.stripped or
            "grafana-alerting" in content_line.stripped):
            return True

    return False

def get_resource_id(resource: ResourceContent) -> Optional[str]:
    """Extract the ID of a resource from its metadata."""
    # First try to find the ID in metadata
    for meta in resource.metadata:
        if meta.key == "id":
            return meta.value

    # If we didn't find an ID in metadata, check URN patterns
    for meta in resource.metadata:
        if meta.key == "urn" and "::canton-network::" in meta.value:
            # Try to extract ID from the end of the URN
            parts = meta.value.split("::")
            if len(parts) >= 3:
                potential_id = parts[-1]
                if potential_id and potential_id != "":
                    return potential_id

    return None

def filter_resources(resources: List[ResourceContent], filter_args: FilterArgs) -> List[ResourceContent]:
    """Apply all filters to resources and return the filtered list."""
    filtered_resources = list(resources)  # Start with all resources

    # Apply operation filters
    if filter_args.include_ops:
        op_types = parse_comma_separated_patterns(filter_args.include_ops)
        if op_types:
            filtered_resources = [r for r in filtered_resources
                                if r.operation.name.lower() in [op.lower() for op in op_types]]

    if filter_args.exclude_ops:
        op_types = parse_comma_separated_patterns(filter_args.exclude_ops)
        if op_types:
            filtered_resources = [r for r in filtered_resources
                                if r.operation.name.lower() not in [op.lower() for op in op_types]]

    # Apply resource type filters
    if filter_args.include_types:
        type_patterns = parse_comma_separated_patterns(filter_args.include_types)
        if type_patterns:
            filtered_resources = [r for r in filtered_resources
                                if matches_pattern(r.resource_type, type_patterns)]

    if filter_args.exclude_types:
        type_patterns = parse_comma_separated_patterns(filter_args.exclude_types)
        if type_patterns:
            filtered_resources = [r for r in filtered_resources
                                if not matches_pattern(r.resource_type, type_patterns)]

    # Apply ID pattern filters
    if filter_args.include_id_pattern:
        id_patterns = parse_comma_separated_patterns(filter_args.include_id_pattern)
        if id_patterns:
            filtered_resources = [r for r in filtered_resources
                                if get_resource_id(r) and matches_pattern(get_resource_id(r), id_patterns)]

    if filter_args.exclude_id_pattern:
        id_patterns = parse_comma_separated_patterns(filter_args.exclude_id_pattern)
        if id_patterns:
            filtered_resources = [r for r in filtered_resources
                                if not (get_resource_id(r) and matches_pattern(get_resource_id(r), id_patterns))]

    # Hide Grafana resources if requested
    if filter_args.hide_grafana:
        filtered_resources = [r for r in filtered_resources if not is_grafana_resource(r)]

    return filtered_resources


def should_display_resource(resource: ResourceContent, filter_args: FilterArgs) -> bool:
    """Legacy function - use filter_resources instead."""
    # Check if we should hide grafana resources
    if filter_args.hide_grafana and is_grafana_resource(resource):
        return False

    # Check for operation filtering
    if filter_args.include_ops:
        op_types = parse_comma_separated_patterns(filter_args.include_ops)
        if op_types:
            # Convert ResourceOp enum value to string and check if it's in the list
            resource_op_name = resource.operation.name.lower()
            if resource_op_name not in [op.lower() for op in op_types]:
                return False

    if filter_args.exclude_ops:
        op_types = parse_comma_separated_patterns(filter_args.exclude_ops)
        if op_types:
            # Convert ResourceOp enum value to string and check if it's in the list
            resource_op_name = resource.operation.name.lower()
            if resource_op_name in [op.lower() for op in op_types]:
                return False

    # Check for resource type filtering
    if filter_args.include_types:
        type_patterns = parse_comma_separated_patterns(filter_args.include_types)
        if type_patterns and not matches_pattern(resource.resource_type, type_patterns):
            return False

    if filter_args.exclude_types:
        type_patterns = parse_comma_separated_patterns(filter_args.exclude_types)
        if type_patterns and matches_pattern(resource.resource_type, type_patterns):
            return False

    # Check for ID-based filtering
    resource_id = get_resource_id(resource)

    # If ID-based filtering is requested, exclude resources without IDs
    if filter_args.include_id_pattern and not resource_id:
        return False

    if resource_id:
        if filter_args.include_id_pattern:
            id_patterns = parse_comma_separated_patterns(filter_args.include_id_pattern)
            if id_patterns and not matches_pattern(resource_id, id_patterns):
                return False

        if filter_args.exclude_id_pattern:
            id_patterns = parse_comma_separated_patterns(filter_args.exclude_id_pattern)
            if id_patterns and matches_pattern(resource_id, id_patterns):
                return False

    # If we reached here, the resource passes all filters
    return True

def should_display_config(config: ConfigContent, filter_args: FilterArgs) -> bool:
    """Check if a configuration should be displayed."""
    # If hide_config is set, don't display any config content
    # Otherwise, only hide if summary or count_only is set
    return not (filter_args.hide_config or filter_args.summary or filter_args.count_only)

def should_display_env(env: EnvironmentContent, filter_args: FilterArgs) -> bool:
    """Check if an environment line should be displayed."""
    return not (filter_args.hide_env or filter_args.summary or filter_args.count_only)

def should_display_header(header: HeaderContent, filter_args: FilterArgs) -> bool:
    """Check if a section header should be displayed."""
    # Always show headers unless we're in summary or count mode
    return not (filter_args.summary or filter_args.count_only)

def should_display_empty(empty: EmptyContent, filter_args: FilterArgs) -> bool:
    """Check if an empty line should be displayed."""
    return not (filter_args.summary or filter_args.count_only)

def should_display_other(other: OtherContent, filter_args: FilterArgs) -> bool:
    """Check if other content should be displayed."""
    return not (filter_args.summary or filter_args.count_only)

def should_display_content(content: ContentType, filter_args: FilterArgs) -> bool:
    """Check if content should be displayed based on filters."""
    if isinstance(content, ResourceContent):
        return should_display_resource(content, filter_args)
    elif isinstance(content, ConfigContent):
        return should_display_config(content, filter_args)
    elif isinstance(content, EnvironmentContent):
        return should_display_env(content, filter_args)
    elif isinstance(content, HeaderContent):
        return should_display_header(content, filter_args)
    elif isinstance(content, EmptyContent):
        return should_display_empty(content, filter_args)
    elif isinstance(content, OtherContent):
        return should_display_other(content, filter_args)
    return False

def filter_content(parsed: ParsedOutput, filter_args: FilterArgs) -> ParsedOutput:
    """Apply filters to the parsed content."""
    # First filter resources based on operation filters
    filtered_resources = [r for r in parsed.resources if should_display_resource(r, filter_args)]

    # Get set of line indices from resources that should be displayed
    resource_indices = set()
    for resource in filtered_resources:
        resource_indices.update(resource.get_line_indices())

    # Return a new ParsedOutput with filtered content
    return ParsedOutput(
        resources=filtered_resources,
        configs=[c for c in parsed.configs if should_display_config(c, filter_args)],
        environments=[e for e in parsed.environments if should_display_env(e, filter_args)],
        headers=[h for h in parsed.headers if should_display_header(h, filter_args)],
        empty_lines=[e for e in parsed.empty_lines if should_display_empty(e, filter_args)],
        other_content=[o for o in parsed.other_content if should_display_other(o, filter_args)]
    )

#
# Output Functions
#

def display_content(content: ContentType) -> None:
    """Display a single content item."""
    if isinstance(content, ResourceContent):
        # Print the resource header
        print(content.content)

        # Print metadata lines
        for line in content.metadata_lines:
            print(line.content)

        # Print content lines
        for line in content.content_lines:
            print(line.content)
    elif isinstance(content, ConfigContent):
        if content.is_header:
            # Redact config blocks
            loaded_text = strip_ansi(content.content).split("Loaded", 1)[1].split("{", 1)[0].strip()
            print(colored(f"Loaded {loaded_text} REDACTED", "blue"))
        # Always skip non-header config content as part of the block
    else:
        # For all other content types, just print the content
        print(content.content)

def display_parsed_content(parsed: ParsedOutput, filter_args: FilterArgs) -> None:
    """Display filtered content in the appropriate format."""
    # Apply all resource filters
    filtered_resources = filter_resources(parsed.resources, filter_args)

    if filter_args.count_only:
        display_count_only(filtered_resources)
    elif filter_args.summary:
        display_summary(filtered_resources)
    else:
        # Display content with proper filtering
        filtered_content = parsed.all_content()
        display_filtered_content(filtered_content, filtered_resources, filter_args)

    if not filter_args.quiet:
        # Display the count of filtered resources
        print(colored(f"Filtered resources: {len(filtered_resources)}", "cyan"))

def display_filtered_content(all_content: List[ContentType], resources: List[ResourceContent], filter_args: FilterArgs) -> None:
    """Display filtered content with proper resource filtering.

    Uses filter_args to determine what content to show:
    - hide_config: Hides configuration blocks
    - hide_env: Hides environment variables and flags
    - clean_output: Hides miscellaneous content that isn't recognized as resources
    """
    # We'll rebuild the output line by line
    output_lines = []    # Start with the Resources header
    output_lines.append("Resources:")

    # Add each filtered resource and its content
    for resource in resources:
        # Add a blank line before each resource (except the first one)
        if output_lines[-1] != "Resources:":
            output_lines.append("")

        # Add the resource header
        output_lines.append(resource.content)

        # Add metadata lines
        for line in resource.metadata_lines:
            output_lines.append(line.content)

        # Add content lines
        for line in resource.content_lines:
            output_lines.append(line.content)

    # Add a line break after resources section
    if resources:
        output_lines.append("")

    # Add OtherContent based on clean_output flag
    # Debug info about OtherContent if verbose
    if filter_args.verbose:
        print(colored("\nDEBUG - Processing OtherContent:", "magenta"))

    for content in all_content:
        if isinstance(content, OtherContent):
            stripped_content = strip_ansi(content.content).strip()
            is_delimiter = stripped_content.startswith("--- Output from") and stripped_content.endswith("---")

            # Debug each OtherContent line if verbose
            if filter_args.verbose:
                print(colored(f"  OtherContent: '{stripped_content[:50]}{'...' if len(stripped_content) > 50 else ''}' | Delimiter: {content.is_delimiter} | Adding: {not filter_args.clean_output}", "magenta"))

            # Only add if we're not in clean output mode
            if not filter_args.clean_output:
                output_lines.append(content.content)
    # With clean_output, we don't show any OtherContent including "More content" indicators and delimiter lines

    # Print all the output lines
    if filter_args.verbose:
        print(colored(f"\nDEBUG - Final output: {len(output_lines)} lines", "yellow"))

    for line in output_lines:
        print(line)

def display_count_only(resources: List[ResourceContent]) -> None:
    """Display only the count of resources by operation type."""
    resources_by_op = defaultdict(int)

    for resource in resources:
        resources_by_op[resource.operation.name] += 1

    # Color mapping for operations
    op_colors = {
        ResourceOp.CREATE.name: "green",
        ResourceOp.UPDATE.name: "yellow",
        ResourceOp.DELETE.name: "red",
        ResourceOp.REPLACE.name: "magenta",
        ResourceOp.READ.name: "blue",
        ResourceOp.UNKNOWN.name: "white"
    }

    # Print the counts by operation
    print(colored("Resource count by operation:", "cyan", attrs=["bold"]))
    for op, count in sorted(resources_by_op.items()):
        color = op_colors.get(op, "white")
        print(colored(f"{op}: ", color) + str(count))

    total = sum(resources_by_op.values())
    print(colored(f"Total: {total}", "cyan", attrs=["bold"]))

def display_summary(resources: List[ResourceContent]) -> None:
    """Display a summary of resources by operation type."""
    resources_by_op = defaultdict(int)

    for resource in resources:
        resources_by_op[resource.operation.name] += 1

    # Color mapping for operations
    op_colors = {
        ResourceOp.CREATE.name: "green",
        ResourceOp.UPDATE.name: "yellow",
        ResourceOp.DELETE.name: "red",
        ResourceOp.REPLACE.name: "magenta",
        ResourceOp.READ.name: "blue",
        ResourceOp.UNKNOWN.name: "white"
    }

    # Print summary with resource types
    print(colored("Resource summary by operation:", "cyan", attrs=["bold"]))

    # Group resources by operation and type
    by_op_and_type = defaultdict(lambda: defaultdict(list))
    for res in resources:
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

    print(colored(f"Total filtered resources: {len(resources)}", "cyan", attrs=["bold"]))

#
# CLI and Main Functions
#

def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Display Pulumi preview outputs from CircleCI jobs or local files"
    )

    # Add a hidden quiet flag
    parser.add_argument(
        "--quiet",
        action="store_true",
        default=False,
        help=argparse.SUPPRESS
    )
    parser.add_argument(
        "job_or_file",
        help="CircleCI job ID, full URL, or local file path with Pulumi output"
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
        "--clean-output",
        action="store_true",
        help="Hide miscellaneous content that isn't recognized as resources, configs, or other structured data"
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

def args_to_filter_args(args) -> FilterArgs:
    """Convert argparse namespace to FilterArgs."""
    return FilterArgs(
        hide_config=getattr(args, 'hide_config', False),
        hide_env=getattr(args, 'hide_env', False),
        hide_grafana=getattr(args, 'hide_grafana', False),
        clean_output=getattr(args, 'clean_output', False),
        include_ops=getattr(args, 'include_ops', None),
        exclude_ops=getattr(args, 'exclude_ops', None),
        include_types=getattr(args, 'include_types', None),
        exclude_types=getattr(args, 'exclude_types', None),
        include_id_pattern=getattr(args, 'include_id_pattern', None),
        exclude_id_pattern=getattr(args, 'exclude_id_pattern', None),
        summary=getattr(args, 'summary', False),
        count_only=getattr(args, 'count_only', False),
        verbose=getattr(args, 'verbose', False),
        quiet=getattr(args, 'quiet', False)
    )

def get_circleci_token(args):
    """Get CircleCI API token from argument or environment variable."""
    token = args.circleci_token or os.environ.get("CIRCLECI_TOKEN")
    if not token:
        print(colored("Error: CIRCLECI_TOKEN not set via argument or environment variable", "red", attrs=["bold"]),
              file=sys.stderr)
        sys.exit(1)
    return token

def extract_job_id(job_or_file):
    """Extract job ID from the input string (works with job ID, URL, or file path)."""
    return job_or_file.split("/")[-1]

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
    """Redact sensitive tokens from URLs."""
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

def process_output_file(output_file, filter_args: FilterArgs):
    """Process the output file - parse, filter, and display.

    Works with both CircleCI job outputs saved to a file and directly provided local files.
    """
    try:
        if filter_args.verbose:
            print(colored(f"Parsing output from {output_file}", "cyan"))

        with open(output_file, 'r') as f:
            content = f.read()

        # Process the content
        lines = content.split('\n')

        # Parse content into structured data
        parsed = parse_content_lines(lines)

        # For debugging
        if filter_args.verbose:
            print(f"Found {len(parsed.resources)} resources")
            for resource in parsed.resources:
                print(f"  Resource: {resource.resource_type} ({resource.operation.name})")

        # Don't modify the resources array here - filtering is handled in display function

        # Display the filtered content
        display_parsed_content(parsed, filter_args)

        return True

    except FileNotFoundError:
        print(colored(f"Error: Output file {output_file} not found", "red"))
        return False
    except Exception as e:
        print(colored(f"Error parsing output file: {e}", "red"))
        import traceback
        traceback.print_exc()
        return False

def main():
    """Main entry point."""
    args = parse_args()

    # Check if help was requested - handle differently
    if len(sys.argv) > 1 and sys.argv[1] in ['-h', '--help']:
        return

    # Create filter args object
    filter_args = args_to_filter_args(args)

    # Check if input is a file path
    if os.path.isfile(args.job_or_file):
        print(f"Processing local file {args.job_or_file} directly...")
        process_output_file(args.job_or_file, filter_args)
        return

    # Otherwise, treat as a CircleCI job ID or URL
    token = get_circleci_token(args)
    job_id = extract_job_id(args.job_or_file)
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

    # Convert args to FilterArgs
    filter_args = args_to_filter_args(args)

    # Process and display the output
    process_output_file(output_file, filter_args)

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
