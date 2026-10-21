#!/usr/bin/env python3
# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import calendar
import datetime
import json
import os
import re
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Optional

DEFAULT_GROUP_NAME = "Upcoming"

DATE_COLUMN_NAME = "Date/Time US EST"
STATUS_COLUMN_NAME = "Submission Status"
NETWORK_COLUMN_NAME = "Network"
ACTIVITY_COLUMN_NAME = "Type of Activity"
VERSION_COLUMN_NAME = "Minor Versions"
DEPENDENCY_COLUMN_NAME = "Dependent On"
DEV_COLUMN_NAME = "Dev"

INITIAL_STATUS = "To Be Confirmed"

NETWORK_DEVNET = "DevNet"
NETWORK_TESTNET = "TestNet"
NETWORK_MAINNET = "MainNet"

ACTIVITY_WEEKLY = "Weekly Upgrades"
ACTIVITY_DAML = "Splice Daml Model Effectivity"
ACTIVITY_LSU = "Protocol Upgrades (LSU)"
ACTIVITY_CONFIG = "Configuration Change"
ACTIVITY_RELEASE = "Release Cadence"

_BOARD_CACHE: dict[int, dict] = {}
_ITEMS_CACHE: dict[int, dict[str, list[str]]] = {}


@dataclass(frozen=True)
class ScheduledEvent:
    title: str
    date: datetime.date
    network: str
    activity: str
    minor_version: str
    time_utc: Optional[str] = None
    depends_on: Optional[str] = None
    dev: bool = False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create/update the monthly Splice release schedule in monday.com."
    )

    parser.add_argument("version", help="Minor version, e.g. 0.8")

    parser.add_argument("month", help="Month in YYYY-MM format, e.g. 2026-08")

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Validate and show changes without modifying monday.com.",
    )

    args = parser.parse_args()

    if re.fullmatch(r"\d+\.\d+", args.version) is None:
        parser.error("version must be in MAJOR.MINOR form, for example 0.8")

    if re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", args.month) is None:
        parser.error("month must be in YYYY-MM format")

    return args


def first_monday_in_month(month: str) -> datetime.date:
    year, month_num = map(int, month.split("-"))

    first_day = datetime.date(year, month_num, 1)

    return first_day + datetime.timedelta(days=(0 - first_day.weekday()) % 7)


def mondays_in_month(month: str) -> int:
    year, month_num = map(int, month.split("-"))

    _, days_in_month = calendar.monthrange(year, month_num)

    first_day = datetime.date(year, month_num, 1)

    first_monday_offset = (0 - first_day.weekday()) % 7

    return (days_in_month - first_monday_offset + 6) // 7


def schedule_date(month: str, weekday: str, week_number: int) -> datetime.date:
    weekdays = {
        "monday": 0,
        "tuesday": 1,
        "wednesday": 2,
        "thursday": 3,
        "friday": 4,
        "saturday": 5,
        "sunday": 6,
    }

    key = weekday.strip().lower()

    if key not in weekdays:
        raise ValueError(f"Unknown weekday: {weekday}")

    return first_monday_in_month(month) + datetime.timedelta(weeks=week_number, days=weekdays[key])


def required_env(name: str) -> str:
    value = os.getenv(name)

    if value is None or value.strip() == "":
        raise RuntimeError(f"Missing required environment variable: {name}")

    return value.strip()


def board_id_from_env() -> int:
    value = required_env("MONDAY_BOARD_ID")

    try:
        return int(value)
    except ValueError as exc:
        raise RuntimeError(f"MONDAY_BOARD_ID must be numeric; got {value!r}") from exc


def monday_request(token: str, query: str, variables: dict) -> dict:
    headers = {"Authorization": token, "Content-Type": "application/json",
               "Accept": "application/graphql-response+json, application/json"}

    if os.getenv("MONDAY_API_VERSION"):
        headers["API-Version"] = os.environ["MONDAY_API_VERSION"]

    request = urllib.request.Request(
        "https://api.monday.com/v2",
        data=json.dumps({"query": query, "variables": variables}).encode("utf-8"),
        headers=headers,
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            body = response.read().decode("utf-8")

    except urllib.error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")

        raise RuntimeError(f"Monday API request failed ({exc.code}): {response_body}") from exc

    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach Monday API: {exc}") from exc

    parsed = json.loads(body)

    if parsed.get("errors"):
        raise RuntimeError(
            "Monday API returned errors: " + json.dumps(parsed["errors"], ensure_ascii=False)
        )

    return parsed


def trigger_website_update() -> None:
    request = urllib.request.Request(required_env("MONDAY_REFRESH_STAGING_CALENDAR"), method="GET")

    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()

    except urllib.error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")

        raise RuntimeError(
            f"Website update trigger failed ({exc.code}): {response_body}"
        ) from exc

    except urllib.error.URLError as exc:
        raise RuntimeError(f"Could not reach website update endpoint: {exc}") from exc


def get_board(token: str, board_id: int) -> dict:
    if board_id in _BOARD_CACHE:
        return _BOARD_CACHE[board_id]

    query = """
    query BoardInfo($boardId: [ID!]!) {
      boards(ids: $boardId) {
        id
        name
        columns {
          id
          title
          type
          settings
        }
        groups {
          id
          title
          archived
          deleted
        }
      }
    }
    """

    response = monday_request(token, query, {"boardId": [board_id]})

    boards = response.get("data", {}).get("boards", [])

    if not boards:
        raise RuntimeError(f"Board not found or not accessible: {board_id}")

    _BOARD_CACHE[board_id] = boards[0]

    return boards[0]


def get_column(board: dict, title: str) -> dict:
    target = title.strip().casefold()

    matches = [
        column
        for column in board.get("columns", [])
        if (str(column.get("title", "")).strip().casefold() == target)
    ]

    if not matches:
        existing = ", ".join(
            sorted(str(column.get("title", "")) for column in board.get("columns", []))
        )

        raise RuntimeError(f"Column {title!r} not found. Board columns are: {existing}")

    if len(matches) > 1:
        raise RuntimeError(f"More than one column is named {title!r}.")

    return matches[0]


def get_group_id(board: dict, group_name: str) -> str:
    target = group_name.strip().casefold()

    matches = [
        group
        for group in board.get("groups", [])
        if (
            not group.get("archived")
            and not group.get("deleted")
            and (str(group.get("title", "")).strip().casefold() == target)
        )
    ]

    if not matches:
        existing = ", ".join(
            str(group.get("title", ""))
            for group in board.get("groups", [])
            if (not group.get("archived") and not group.get("deleted"))
        )

        raise RuntimeError(f"Group {group_name!r} not found. Active groups are: {existing}")

    if len(matches) > 1:
        raise RuntimeError(f"More than one active group is named {group_name!r}.")

    return str(matches[0]["id"])


def column_labels(column: dict) -> set[str]:
    settings = column.get("settings") or {}

    if isinstance(settings, str):
        try:
            settings = json.loads(settings)
        except json.JSONDecodeError:
            return set()

    if not isinstance(settings, dict):
        return set()

    labels: set[str] = set()

    raw_labels = settings.get("labels", [])

    if isinstance(raw_labels, list):
        for entry in raw_labels:
            if isinstance(entry, dict):
                label = entry.get("label") or entry.get("name")

                if isinstance(label, str) and label:
                    labels.add(label)

            elif isinstance(entry, str) and entry:
                labels.add(entry)

    elif isinstance(raw_labels, dict):
        for entry in raw_labels.values():
            if isinstance(entry, str) and entry:
                labels.add(entry)

            elif isinstance(entry, dict):
                label = entry.get("label") or entry.get("name")

                if isinstance(label, str) and label:
                    labels.add(label)

    return labels


def ensure_column_type(column: dict, allowed: set[str]) -> None:
    actual = str(column.get("type", "")).lower()

    if actual not in allowed:
        raise RuntimeError(
            f"Column {column['title']!r} has type {actual!r}; expected one of {sorted(allowed)}"
        )


def require_label(column: dict, label: str) -> None:
    labels = column_labels(column)

    if labels and label not in labels:
        raise RuntimeError(
            f"Column {column['title']!r} does not contain label {label!r}. Available labels: {sorted(labels)}"
        )


def preflight(token: str, board_id: int, group_name: str) -> tuple[dict, str]:
    board = get_board(token, board_id)

    date_col = get_column(board, DATE_COLUMN_NAME)

    status_col = get_column(board, STATUS_COLUMN_NAME)

    network_col = get_column(board, NETWORK_COLUMN_NAME)

    activity_col = get_column(board, ACTIVITY_COLUMN_NAME)

    version_col = get_column(board, VERSION_COLUMN_NAME)

    dependency_col = get_column(board, DEPENDENCY_COLUMN_NAME)

    dev_col = get_column(board, DEV_COLUMN_NAME)

    ensure_column_type(date_col, {"date"})

    ensure_column_type(status_col, {"status", "color"})

    ensure_column_type(network_col, {"status", "color", "dropdown", "text"})

    ensure_column_type(activity_col, {"status", "color", "dropdown", "text"})

    ensure_column_type(version_col, {"dropdown", "status", "color", "text"})

    ensure_column_type(dependency_col, {"dependency"})

    ensure_column_type(dev_col, {"checkbox"})

    require_label(status_col, INITIAL_STATUS)

    for label in (NETWORK_DEVNET, NETWORK_TESTNET, NETWORK_MAINNET):
        require_label(network_col, label)

    for label in (ACTIVITY_WEEKLY, ACTIVITY_DAML, ACTIVITY_LSU, ACTIVITY_CONFIG):
        require_label(activity_col, label)

    return (board, get_group_id(board, group_name))


def choice_value(column: dict, label: str | bool):
    column_type = str(column.get("type", "")).lower()

    if column_type in {"status", "color"}:
        return {"label": label}

    if column_type == "dropdown":
        return {"labels": [label]}

    if column_type in {"text", "long_text"}:
        return label

    if column_type == "checkbox":
        return {"checked": "true"} if label else None

    raise RuntimeError(f"Unsupported choice column type {column_type!r} for {column['title']!r}")


def date_value(event_date: datetime.date, time_utc: Optional[str]) -> dict:
    value = {"date": event_date.isoformat()}

    if time_utc is not None:
        if re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", time_utc) is None:
            raise ValueError(f"Invalid UTC time {time_utc!r}; expected HH:MM")

        value["time"] = f"{time_utc}:00"

    return value


def build_column_values(
    board: dict, event: ScheduledEvent, include_submission_status: bool
) -> dict:
    date_col = get_column(board, DATE_COLUMN_NAME)

    network_col = get_column(board, NETWORK_COLUMN_NAME)

    activity_col = get_column(board, ACTIVITY_COLUMN_NAME)

    version_col = get_column(board, VERSION_COLUMN_NAME)

    dev_col = get_column(board, DEV_COLUMN_NAME)

    values = {
        str(date_col["id"]): date_value(event.date, event.time_utc),
        str(network_col["id"]): choice_value(network_col, event.network),
        str(activity_col["id"]): choice_value(activity_col, event.activity),
        str(version_col["id"]): choice_value(version_col, event.minor_version),
        str(dev_col["id"]): choice_value(dev_col, event.dev),
    }

    if include_submission_status:
        status_col = get_column(board, STATUS_COLUMN_NAME)

        values[str(status_col["id"])] = choice_value(status_col, INITIAL_STATUS)

    return values


def load_existing_items(token: str, board_id: int) -> dict[str, list[str]]:
    if board_id in _ITEMS_CACHE:
        return _ITEMS_CACHE[board_id]

    items_by_name: dict[str, list[str]] = {}

    query = """
    query BoardItems($boardId: [ID!]!) {
      boards(ids: $boardId) {
        items_page(limit: 500) {
          cursor
          items {
            id
            name
          }
        }
      }
    }
    """

    response = monday_request(token, query, {"boardId": [board_id]})

    boards = response.get("data", {}).get("boards", [])

    if not boards:
        raise RuntimeError(f"Board not found: {board_id}")

    page = boards[0].get("items_page") or {}

    for item in page.get("items", []):
        items_by_name.setdefault(str(item["name"]), []).append(str(item["id"]))

    cursor = page.get("cursor")

    next_query = """
    query MoreItems($cursor: String!) {
      next_items_page(cursor: $cursor) {
        cursor
        items {
          id
          name
        }
      }
    }
    """

    while cursor:
        response = monday_request(token, next_query, {"cursor": cursor})

        page = response.get("data", {}).get("next_items_page") or {}

        for item in page.get("items", []):
            items_by_name.setdefault(str(item["name"]), []).append(str(item["id"]))

        cursor = page.get("cursor")

    _ITEMS_CACHE[board_id] = items_by_name

    return items_by_name


def create_item(
    token: str, board_id: int, group_id: str, board: dict, event: ScheduledEvent
) -> str:
    mutation = """
    mutation CreateItem(
      $boardId: ID!,
      $groupId: String!,
      $itemName: String!,
      $columnValues: JSON!
    ) {
      create_item(
        board_id: $boardId,
        group_id: $groupId,
        item_name: $itemName,
        column_values: $columnValues,
        create_labels_if_missing: true
      ) {
        id
      }
    }
    """

    response = monday_request(
        token,
        mutation,
        {
            "boardId": board_id,
            "groupId": group_id,
            "itemName": event.title,
            "columnValues": json.dumps(
                build_column_values(board, event, include_submission_status=True)
            ),
        },
    )

    return str(response["data"]["create_item"]["id"])


def update_item(
    token: str, board_id: int, board: dict, item_id: str, event: ScheduledEvent
) -> None:
    mutation = """
    mutation UpdateItem(
      $boardId: ID!,
      $itemId: ID!,
      $columnValues: JSON!
    ) {
      change_multiple_column_values(
        board_id: $boardId,
        item_id: $itemId,
        column_values: $columnValues,
        create_labels_if_missing: true
      ) {
        id
      }
    }
    """

    monday_request(
        token,
        mutation,
        {
            "boardId": board_id,
            "itemId": item_id,
            "columnValues": json.dumps(
                build_column_values(board, event, include_submission_status=False)
            ),
        },
    )


def describe_event(event: ScheduledEvent) -> str:
    when = event.date.isoformat()

    if event.time_utc:
        when += f" {event.time_utc} UTC"

    return f"{when} | {event.network} | {event.activity} | {event.minor_version}" + (" (Dev)" if event.dev else "")


def upsert_event(
    token: str, board_id: int, group_id: str, board: dict, event: ScheduledEvent, dry_run: bool
) -> Optional[str]:
    items = load_existing_items(token, board_id)

    matches = items.get(event.title, [])

    if len(matches) > 1:
        raise RuntimeError(
            f"Cannot safely update {event.title!r}: multiple exact-name items exist: {matches}"
        )

    details = describe_event(event)

    if matches:
        item_id = matches[0]

        if dry_run:
            print(f"WOULD UPDATE {item_id}: {event.title} -> {details}")
        else:
            update_item(token, board_id, board, item_id, event)

            print(f"UPDATED {item_id}: {event.title} -> {details}")

        return item_id

    if dry_run:
        print(f"WOULD CREATE: {event.title} -> {details}")

        return None

    item_id = create_item(token, board_id, group_id, board, event)

    items.setdefault(event.title, []).append(item_id)

    print(f"CREATED {item_id}: {event.title} -> {details}")

    return item_id


def set_dependency(
    token: str, board_id: int, board: dict, item_id: str, dependency_item_id: str
) -> None:
    dependency_col = get_column(board, DEPENDENCY_COLUMN_NAME)

    mutation = """
    mutation SetDependency(
      $boardId: ID!,
      $itemId: ID!,
      $columnValues: JSON!
    ) {
      change_multiple_column_values(
        board_id: $boardId,
        item_id: $itemId,
        column_values: $columnValues
      ) {
        id
      }
    }
    """

    monday_request(
        token,
        mutation,
        {
            "boardId": board_id,
            "itemId": item_id,
            "columnValues": json.dumps(
                {str(dependency_col["id"]): {"item_ids": [str(dependency_item_id)]}}
            ),
        },
    )


def make_schedule(version: str, month: str) -> list[ScheduledEvent]:
    events: list[ScheduledEvent] = []

    patch_count = mondays_in_month(month)

    specs = [
        {
            "network": NETWORK_DEVNET,
            "weekly_offset": 0,
            "daml_week": 2,
            "freeze_week": 2,
            "freeze_day": "tuesday",
            "lsu_week": 2,
            "lsu_day": "wednesday",
            "config_week": 3,
            "daml_title": (f"DevNet New Daml models introduced by Splice {version}.x take effect"),
            "freeze_title": (f"DevNet Topology Freeze ({version} Required) (MONTH YEAR)"),
            "lsu_title": (f"DevNet LSU ({version} Required) (MONTH YEAR)"),
            "config_title": (f"DevNet Breaking Config Changes ({version} Required)"),
        },
        {
            "network": NETWORK_TESTNET,
            "weekly_offset": 1,
            "daml_week": 3,
            "freeze_week": 3,
            "freeze_day": "tuesday",
            "lsu_week": 3,
            "lsu_day": "wednesday",
            "config_week": 4,
            "daml_title": (f"TestNet New Daml models introduced by Splice {version}.x take effect"),
            "freeze_title": (f"TestNet Topology Freeze ({version} Required) (MONTH YEAR)"),
            "lsu_title": (f"TestNet LSU ({version} Required) (MONTH YEAR)"),
            "config_title": (f"TestNet Breaking Config Changes ({version} Required)"),
        },
        {
            "network": NETWORK_MAINNET,
            "weekly_offset": 2,
            "daml_week": 4,
            "freeze_week": 4,
            "freeze_day": "friday",
            "lsu_week": 4,
            "lsu_day": "saturday",
            "config_week": 5,
            "daml_title": (f"MainNet New Daml models introduced by Splice {version}.x take effect"),
            "freeze_title": (f"MainNet Topology Freeze ({version})"),
            "lsu_title": (f"MainNet LSU ({version} Required) (MONTH YEAR)"),
            "config_title": (f"MainNet Breaking Config Changes ({version} Required)"),
        },
    ]

    for spec in specs:
        network = spec["network"]

        lsu_date = schedule_date(month, str(spec["lsu_day"]), int(spec["lsu_week"]))

        lsu_month_year = f"{calendar.month_name[lsu_date.month]} {lsu_date.year:04d}"

        freeze_title = str(spec["freeze_title"]).replace("MONTH YEAR", lsu_month_year)

        lsu_title = str(spec["lsu_title"]).replace("MONTH YEAR", lsu_month_year)

        for patch in range(patch_count):
            events.append(
                ScheduledEvent(
                    title=(f"{network} upgrades to Splice {version}.{patch}"),
                    date=schedule_date(month, "monday", patch + int(spec["weekly_offset"])),
                    network=str(network),
                    activity=(ACTIVITY_WEEKLY),
                    minor_version=(version),
                )
            )

        events.extend(
            [
                ScheduledEvent(
                    title=str(spec["daml_title"]),
                    date=schedule_date(month, "tuesday", int(spec["daml_week"])),
                    time_utc="12:00",
                    network=str(network),
                    activity=(ACTIVITY_DAML),
                    minor_version=(version),
                ),
                ScheduledEvent(
                    title=freeze_title,
                    date=schedule_date(month, str(spec["freeze_day"]), int(spec["freeze_week"])),
                    time_utc="13:00",
                    network=str(network),
                    activity=(ACTIVITY_LSU),
                    minor_version=(version),
                ),
                ScheduledEvent(
                    title=lsu_title,
                    date=lsu_date,
                    time_utc="13:00",
                    network=str(network),
                    activity=(ACTIVITY_LSU),
                    minor_version=(version),
                    depends_on=freeze_title,
                ),
                ScheduledEvent(
                    title=str(spec["config_title"]),
                    date=schedule_date(month, "tuesday", int(spec["config_week"])),
                    time_utc="12:00",
                    network=str(network),
                    activity=(ACTIVITY_CONFIG),
                    minor_version=(version),
                ),
            ]
        )

    # Dev events
    for patch in range(patch_count):
        events.append(
            ScheduledEvent(
                title=(f"Cut Splice {version}.{patch}"),
                date=schedule_date(month, "thursday", patch-1),
                network="",
                activity=(ACTIVITY_RELEASE),
                minor_version=(version),
                dev=True,
            )
        )

    events.append(
        ScheduledEvent(
            title=(f"Cut Canton & WG releases for Splice {version}.0"),
            date=schedule_date(month, "tuesday", -1),
            network="",
            activity=(ACTIVITY_RELEASE),
            minor_version=(version),
            dev=True,
        )
    )

    events.append(
        ScheduledEvent(
            title=(f"Canton version for Splice {version}.0 deployed to CILR"),
            date=schedule_date(month, "monday", -2),
            network="",
            activity=(ACTIVITY_RELEASE),
            minor_version=(version),
            dev=True,
        )
    )

    events.append(
        ScheduledEvent(
            title=(f"Splice Upgrades to new Canton minor release Line (if applicable)"),
            date=schedule_date(month, "wednesday", -3),
            network="",
            activity=(ACTIVITY_RELEASE),
            minor_version=(version),
            dev=True,
        )
    )

    events.append(
        ScheduledEvent(
            title=(f"Cut Canton & WG release line for Splice {version}.0 (if applicable)"),
            date=schedule_date(month, "monday", -3),
            network="",
            activity=(ACTIVITY_RELEASE),
            minor_version=(version),
            dev=True,
        )
    )

    return events


def main() -> None:
    args = parse_args()

    token = required_env("MONDAY_API_TOKEN")

    board_id = board_id_from_env()

    group_name = os.getenv("MONDAY_GROUP_NAME", DEFAULT_GROUP_NAME).strip()

    first_monday = first_monday_in_month(args.month)

    print()

    print(f"Splice {args.version}.x")

    print(f"Monday board: {board_id}")

    print(f"Target group: {group_name}")

    print(f"First DevNet Monday: {first_monday.isoformat()}")

    if args.dry_run:
        print("DRY RUN — validating the board and showing changes only.")

    board, group_id = preflight(token, board_id, group_name)

    print(f"Board name: {board.get('name', '')}")

    print("Preflight validation: OK")

    events = make_schedule(args.version, args.month)

    print(f"Schedule events: {len(events)}")

    print()

    item_ids_by_title: dict[str, str] = {}

    current_network: Optional[str] = None

    for event in events:
        if event.network != current_network:
            current_network = event.network

            print(current_network)

        item_id = upsert_event(token, board_id, group_id, board, event, args.dry_run)

        if item_id:
            item_ids_by_title[event.title] = item_id

    print()

    print("Dependencies")

    for event in events:
        if not event.depends_on:
            continue

        if args.dry_run:
            print(f"WOULD LINK: {event.title} depends on {event.depends_on}")

            continue

        item_id = item_ids_by_title.get(event.title)

        dependency_item_id = item_ids_by_title.get(event.depends_on)

        if not item_id:
            raise RuntimeError(f"Could not find Monday item for {event.title!r}")

        if not dependency_item_id:
            raise RuntimeError(f"Could not find dependency item {event.depends_on!r}")

        set_dependency(token, board_id, board, item_id, dependency_item_id)

        print(f"LINKED: {event.title} depends on {event.depends_on}")

    print()

    if args.dry_run:
        print("SKIPPED: Website update trigger (dry run)")
    else:
        trigger_website_update()
        print("TRIGGERED: Website update")

    print("Done.")


if __name__ == "__main__":
    main()
