#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Summarizes claimed, expired, and unclaimed minting rewards for a given beneficiary
within a specified time range and weight, based on SvRewardCoupon activity.
"""

import aiohttp
import asyncio
import argparse
from concurrent.futures import Future, ThreadPoolExecutor
from decimal import *
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from enum import Enum
import hashlib
import json
import logging
import colorlog
import os
from typing import Optional, Self
import time
import sys

from datetime import datetime, timedelta
from typing import List, Tuple

# Set precision and rounding mode
getcontext().prec = 38
getcontext().rounding = ROUND_HALF_EVEN

# Ensure log directory exists before logger initialization
log_directory = "log"
if not os.path.exists(log_directory):
    os.makedirs(log_directory)

def _default_logger(name, loglevel):
    cli_handler = colorlog.StreamHandler()
    cli_handler.setFormatter(
        colorlog.ColoredFormatter(
            "%(log_color)s%(asctime)s - %(levelname)s:%(name)s:%(message)s",
            log_colors={
                "DEBUG": "cyan",
                "INFO": "green",
                "WARNING": "yellow",
                "ERROR": "red",
                "CRITICAL": "red,bg_white",
            },
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    )
    file_handler = logging.FileHandler("log/unclaimed_sv_rewards.log")
    file_handler.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s:%(name)s:%(message)s", datefmt="%Y-%m-%d %H:%M:%S"))

    logger = colorlog.getLogger(name)
    logger.addHandler(cli_handler)
    logger.addHandler(file_handler)
    logger.setLevel(loglevel)

    return logger


# Global logger, always accessible
LOG = _default_logger("global", "INFO")

def non_negative_int(value):
    ivalue = int(value)
    if ivalue < 0:
        raise argparse.ArgumentTypeError(f"{value} is invalid: must be a non-negative integer")
    return ivalue

def _parse_cli_args() -> argparse.Namespace:
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description="""Scans the transaction log over a given time range and reports statistics on SvRewardCoupon contracts
        (claimed, expired, and unclaimed) for a specific beneficiary. It calculates the corresponding reward amounts
        based on the IssuingMiningRound contracts. This script helps identify unclaimed minting rights due to
        expired or unexercised SvRewardCoupons within the specified window.
        """
    )
    parser.add_argument(
        "scan_urls",
        nargs="+",
        help="Address(es) of the Splice Scan server(s). Multiple URLs can be provided for round-robin failover.",
    )
    parser.add_argument("--loglevel", help="Sets the log level", default="INFO")
    parser.add_argument(
        "--page-size",
        type=int,
        default=1000,
        help="Number of transactions to fetch per network request",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=10,
        help="Maximum number of concurrent chunk workers (async tasks).",
    )
    parser.add_argument(
        "--chunk-size-in-hours",
        type=float,
        default=1.0,
        help="Size of each processing chunk, expressed in hours (e.g. 0.5, 1, 24).",
    )
    parser.add_argument(
        "--grace-period-for-mining-rounds-in-minutes",
        type=int,
        default=60,
        help=(
            "Number of minutes to extend the end-record-time when collecting mining rounds. "
            "Used to ensure all relevant mining rounds are included for rewards created near "
            "the end of the time range."
        ),
    )
    parser.add_argument(
        "--cache-file-path",
        help="File path to save application state to. "
        "If the file exists, processing will resume from the persisted state."
        "Otherwise, processing will start from begin-record-time provided.",
    )
    parser.add_argument(
        "--rebuild-cache",
        action="store_true",
        help="Force the cache to be rebuilt from scratch.",
    )
    parser.add_argument(
        "--beneficiary",
        help="The party for which unclaimed rewards should be calculated.",
        required=True,
    )
    parser.add_argument(
        "--begin-migration-id",
        help="The migration id that was active at begin-record-time.",
        required=True,
    )
    parser.add_argument(
        "--begin-record-time",
        help="Start of the record time range (exclusive) to consider SvRewardCoupon creation. Expected in ISO format: 2025-07-01T10:30:00Z.",
        required=True,
    )
    parser.add_argument(
        "--end-record-time",
        help="End of the record time range (inclusive) to consider SvRewardCoupon creation. Expected in ISO format: 2025-07-01T12:30:00Z",
        required=True,
    )
    parser.add_argument(
        "--weight",
        type=non_negative_int,
        help="Weight of sv coupon rewards to consider",
        required=True,
    )
    parser.add_argument(
        "--already-minted-weight",
        type=non_negative_int,
        help="Weight already minted for the time range provided",
        required=True,
    )
    return parser.parse_args()

def _log_uncaught_exceptions():
    # Set up exception handling (write unhandled exceptions to log)
    def handle_exception(exc_type, exc_value, exc_traceback):
        if issubclass(exc_type, KeyboardInterrupt):
            sys.__excepthook__(exc_type, exc_value, exc_traceback)
            return

        LOG.error("Uncaught exception", exc_info=(exc_type, exc_value, exc_traceback))

    sys.excepthook = handle_exception


class TemplateQualifiedNames:
    sv_reward_coupon = "Splice.Amulet:SvRewardCoupon"
    issuing_mining_round = "Splice.Round:IssuingMiningRound"
    closed_mining_round = "Splice.Round:ClosedMiningRound"


@dataclass
class PaginationKey:
    last_migration_id: int
    last_record_time: str

    def __str__(self):
        return str((self.last_migration_id, self.last_record_time))

    def to_json(self):
        return {
            "after_record_time": self.last_record_time,
            "after_migration_id": self.last_migration_id,
        }

    @classmethod
    def from_json(cls, json):
        return cls(json["after_migration_id"], json["after_record_time"])

@dataclass
class ScanClient:
    session: aiohttp.ClientSession
    urls: list[str]
    page_size: int
    call_count: int = 0
    retry_count: int = 0
    current_url_index: int = 0
    UPDATES_PATH = "/api/scan/v2/updates"

    def _get_current_url(self) -> str:
        """Get the current URL from the round-robin list."""
        return self.urls[self.current_url_index]

    def _rotate_to_next_url(self):
        """Move to the next URL in the round-robin list."""
        self.current_url_index = (self.current_url_index + 1) % len(self.urls)
        LOG.info(f"Rotating to next scan URL: {self._get_current_url()}")

    async def updates(self, after: Optional[PaginationKey]):
        payload = {"page_size": self.page_size}
        if after:
            payload["after"] = after.to_json()

        json = await self.__post_with_retry_on_statuses(
            f"{self._get_current_url()}{self.UPDATES_PATH}",
            payload=payload,
            max_retries=30,
            delay_seconds=0.5,
            statuses={404, 429, 500, 503},
        )
        return json["transactions"]

    async def __post_with_retry_on_statuses(
        self, url, payload, max_retries, delay_seconds, statuses
    ):
        assert max_retries >= 1
        retry = 0
        self.call_count += 1
        last_response = None
        total_attempts = max_retries * len(self.urls)

        while retry < total_attempts:
            try:
                response = await self.session.post(url, json=payload)
                last_response = response
                if response.status in statuses:
                    LOG.debug(
                        f"Request to {url} with payload {payload} failed with status {response.status}"
                    )
                    retry += 1
                    if retry < total_attempts:
                        self.retry_count += 1
                        self._rotate_to_next_url()
                        url = f"{self._get_current_url()}{self.UPDATES_PATH}"
                    await asyncio.sleep(delay_seconds)
                else:
                    response.raise_for_status()
                    return await response.json()
            except (aiohttp.ClientError, asyncio.TimeoutError) as e:
                LOG.debug(
                    f"Request to {url} with payload {payload} failed with error: {e}"
                )
                retry += 1
                if retry < total_attempts:
                    self.retry_count += 1
                    self._rotate_to_next_url()
                    url = f"{self._get_current_url()}{self.UPDATES_PATH}"
                    await asyncio.sleep(delay_seconds)
                else:
                    LOG.error(f"Exceeded max retries {total_attempts} across all URLs, giving up")
                    raise RuntimeError("POST failed: no response received in any attempt")

        LOG.error(f"Exceeded max retries {total_attempts} across all URLs, giving up")
        if last_response is not None:
            last_response.raise_for_status()

        raise RuntimeError("POST failed: no response received in any attempt")


# Daml Decimals have a precision of 38 and a scale of 10, i.e., 10 digits after the decimal point.
# Rounding is round_half_even.
class DamlDecimal:
    def __init__(self, decimal):
        if isinstance(decimal, str):
            self.decimal = Decimal(decimal).quantize(
                Decimal("0.0000000001"), rounding=ROUND_HALF_EVEN
            )
        elif isinstance(decimal, int):
            self.decimal = Decimal(decimal).quantize(
                Decimal("0.0000000001"), rounding=ROUND_HALF_EVEN
            )
        else:
            self.decimal = decimal.quantize(
                Decimal("0.0000000001"), rounding=ROUND_HALF_EVEN
            )

    def to_json(self) -> str:
        return format(self.decimal, ".10f")

    @classmethod
    def from_json(cls, json_str: str):
        return cls(Decimal(json_str))

    def __mul__(self, other):
        return DamlDecimal(self.decimal * other.decimal)

    def __add__(self, other):
        other = DamlDecimal(other) if not isinstance(other, DamlDecimal) else other
        return DamlDecimal((self.decimal + other.decimal))

    def __rmul__(self, other):
        return DamlDecimal(other * self.decimal)

    def __radd__(self, other):
        return DamlDecimal(other + self.decimal)

    def __sub__(self, other):
        return DamlDecimal(self.decimal - other.decimal)

    def __rsub__(self, other):
        return DamlDecimal(other - self.decimal)

    def __truediv__(self, other):
        return DamlDecimal(self.decimal / other.decimal)

    def __str__(self):
        return self.decimal.__str__()

    def __repr__(self):
        return self.decimal.__str__()

    def __eq__(self, other):
        return self.decimal == other.decimal

    def __gt__(self, other):
        return self.decimal > other.decimal

@dataclass
class TemplateId:
    template_id: str
    package_id: str
    qualified_name: str

    def __init__(self, template_id):
        self.template_id = template_id
        (package_id, qualified_name) = template_id.split(":", 1)
        self.package_id = package_id
        self.qualified_name = qualified_name

    def __str__(self):
        return self.template_id

# Wrapper around LF values to easily work with the protobuf encoding
@dataclass
class LfValue:
    value: dict

    # template SvRewardCoupon -> beneficiary
    def get_sv_reward_coupon_beneficiary(self):
        return self.__get_record_field("beneficiary").__get_party()

    # template SvRewardCoupon -> sv
    def get_sv_reward_coupon_sv(self):
        return self.__get_record_field("sv").__get_party()

    # template SvRewardCoupon -> round
    def get_sv_reward_coupon_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template SvRewardCoupon -> weight
    def get_sv_reward_coupon_weight(self):
        return self.__get_record_field("weight").__get_int64()

    # template IssuingMiningRound -> round
    def get_issuing_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template IssuingMiningRound -> issuancePerSvRewardCoupon
    def get_issuing_mining_round_issuance_per_sv_reward(self):
        return self.__get_record_field("issuancePerSvRewardCoupon").__get_numeric()

     # template ClosedMiningRound -> issuancePerSvRewardCoupon
    def get_closed_mining_round_issuance_per_sv_reward(self):
        return self.__get_record_field("issuancePerSvRewardCoupon").__get_numeric()

    # template OpenMiningRound -> round
    def get_open_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template ClosedMiningRound -> round
    def get_closed_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    def __get_numeric(self) -> DamlDecimal:
        try:
            return DamlDecimal(self.value)
        except Exception as e:
            raise LfValueParseException(self, "numeric", e)

    def __get_party(self) -> str:
        if isinstance(self.value, str):
            return self.value
        else:
            raise LfValueParseException(
                self, "party", f"Expected string, got {type(self.value)}"
            )

    def __get_int64(self) -> int:
        try:
            return int(self.value)
        except Exception as e:
            raise LfValueParseException(self, "int64", e)

    def get_contract_id(self) -> str:
        if isinstance(self.value, str):
            return self.value
        else:
            raise LfValueParseException(
                self, "contract id", f"Expected string, got {type(self.value)}"
            )

    def __get_record_field(self, field_name: str) -> Self:
        try:
            if field_name in self.value:
                return LfValue(self.value[field_name])
            else:
                raise LfValueParseException(
                    self, "record", f"Missing record field {field_name}"
                )
        except Exception as e:
            raise LfValueParseException(self, "record", e)

    def __get_round_number(self) -> int:
        return self.__get_record_field("number").__get_int64()



class Event:
    def parse(json: dict):
        event_id = json["event_id"]
        template_id = TemplateId(json["template_id"])
        contract_id = json["contract_id"]
        if "create_arguments" in json:
            return CreatedEvent(
                event_id, template_id, contract_id, LfValue(json["create_arguments"])
            )
        else:
            return ExercisedEvent(
                event_id,
                template_id,
                json["choice"],
                contract_id,
                LfValue(json["choice_argument"]),
                LfValue(json["exercise_result"]),
                json["child_event_ids"],
                json["consuming"],
                json["acting_parties"],
            )

@dataclass
class ExercisedEvent(Event):
    event_id: str
    template_id: TemplateId
    choice_name: str
    contract_id: str
    exercise_argument: LfValue
    exercise_result: LfValue
    child_event_ids: list
    is_consuming: bool
    acting_parties: list[str]

    def to_json(self):
        return {
            "event_id:":self.event_id,
            "template_id": self.template_id.template_id,
            "contract_id": self.contract_id,
            "choice": self.choice_name,
            "choice_argument": self.exercise_argument.value,
            "exercise_result": self.exercise_result.value,
            "child_event_ids": self.child_event_ids,
            "consuming": self.is_consuming,
            "acting_parties": self.acting_parties,
        }

@dataclass
class CreatedEvent(Event):
    event_id: str
    template_id: TemplateId
    contract_id: str
    payload: LfValue

    @classmethod
    def from_json(cls, json):
        return cls(
            json["event_id"],
            TemplateId(json["template_id"]),
            json["contract_id"],
            LfValue(json["create_arguments"]),
        )

    def to_json(self):
        return {
            "event_id:":self.event_id,
            "template_id": self.template_id.template_id,
            "contract_id": self.contract_id,
            "create_arguments": self.payload.value,
        }

@dataclass
class TransactionTree:
    root_event_ids: list[str]
    events_by_id: dict[str, Event]
    migration_id: int
    record_time: datetime
    update_id: str
    workflow_id: str
    synchronizer_id: str

    def __init__(
            self,
            root_event_ids,
            events_by_id,
            migration_id,
            record_time,
            update_id,
            workflow_id,
            synchronizer_id,
    ):
        self.root_event_ids = root_event_ids
        self.events_by_id = events_by_id
        self.record_time = record_time
        self.migration_id = migration_id
        self.update_id = update_id
        self.workflow_id = workflow_id
        self.synchronizer_id = synchronizer_id

    def parse(json: dict):
        return TransactionTree(
            json["root_event_ids"],
            {
                event_id: Event.parse(event)
                for event_id, event in json["events_by_id"].items()
            },
            json["migration_id"],
            datetime.fromisoformat(json["record_time"]),
            json["update_id"],
            json["workflow_id"],
            json["synchronizer_id"],
        )

    def to_json(self):
        return {
            "root_event_ids": self.root_event_ids,
            "events_by_id": {
                event_id: event.to_json()
                for event_id, event in self.events_by_id.items()
            },
            "migration_id": self.migration_id,
            "record_time": self.record_time.isoformat(),
            "update_id": self.update_id,
            "workflow_id": self.workflow_id,
            "synchronizer_id": self.synchronizer_id,
        }

    def with_only_event(self, event: Event):
        return TransactionTree(
            root_event_ids=[event.event_id],
            events_by_id={event.event_id: event},
            migration_id=self.migration_id,
            record_time=self.record_time,
            update_id=self.update_id,
            workflow_id=self.workflow_id,
            synchronizer_id=self.synchronizer_id,
        )


class LfValueParseException(Exception):
    def __init__(self, value, type, details):
        message = f"Could not parse value {value.value} as {type}: {details}"
        self.value = value.value
        self.type = type
        self.details = details
        self.message = message
        super().__init__(message)

def _relevant_args_fingerprint(args: argparse.Namespace) -> str:
    """Compute a fingerprint based on relevant args that affect state validity"""
    relevant = {
        "beneficiary": args.beneficiary,
        "begin_record_time": args.begin_record_time,
        "end_record_time": args.end_record_time,
        "begin_migration_id": args.begin_migration_id,
        "weight": args.weight,
        "already_minted_weight": args.already_minted_weight,
        "grace_period_for_mining_rounds_in_minutes": args.grace_period_for_mining_rounds_in_minutes,
        "chunk_size_in_hours": args.chunk_size_in_hours,
    }
    serialized = json.dumps(relevant, sort_keys=True)
    return hashlib.sha256(serialized.encode()).hexdigest()

@dataclass
class RewardSummary:
    reward_expired_count: int
    reward_claimed_count: int
    reward_expired_total_amount: DamlDecimal
    reward_claimed_total_amount: DamlDecimal

    def to_json(self):
        return {
            "reward_expired_count": self.reward_expired_count,
            "reward_claimed_count": self.reward_claimed_count,
            "reward_expired_total_amount": self.reward_expired_total_amount.to_json(),
            "reward_claimed_total_amount": self.reward_claimed_total_amount.to_json(),
        }

    @classmethod
    def from_json(cls, json):
        return cls(
            reward_expired_count=json["reward_expired_count"],
            reward_claimed_count=json["reward_claimed_count"],
            reward_expired_total_amount=DamlDecimal(json["reward_expired_total_amount"]),
            reward_claimed_total_amount=DamlDecimal(json["reward_claimed_total_amount"]),
        )

class RewardStatus(Enum):
    CLAIMED = "claimed"
    EXPIRED = "expired"

@dataclass
class Reward:
    round: int
    weight: DamlDecimal
    sv: str
    beneficiary: str
    contract_id: str

    def to_json(self):
        return {
            "round": self.round,
            "weight": self.weight,
            "sv": self.sv,
            "beneficiary": self.beneficiary,
            "contract_id": self.contract_id,
        }

    @classmethod
    def from_json(cls, json):
        return cls(
            round=json["round"],
            weight=json["weight"],
            sv=json["sv"],
            beneficiary=json["beneficiary"],
            contract_id=json["contract_id"],
        )

@dataclass
class IssuingRound:
    round: int
    record_time: datetime
    issuance_per_sv_reward: DamlDecimal

    def to_json(self):
        return {
            "round": self.round,
            "record_time": self.record_time.isoformat(),
            "issuance_per_sv_reward": self.issuance_per_sv_reward.to_json(),
        }

    @classmethod
    def from_json(cls, json):
        return cls(
            round=json["round"],
            record_time=datetime.fromisoformat(json["record_time"]),
            issuance_per_sv_reward=DamlDecimal(json["issuance_per_sv_reward"]),
        )

@dataclass
class ClosedRound:
    round: int
    record_time: datetime
    issuance_per_sv_reward: DamlDecimal

    def to_json(self):
        return {
            "round": self.round,
            "record_time": self.record_time.isoformat(),
            "issuance_per_sv_reward": self.issuance_per_sv_reward.to_json(),
        }

    @classmethod
    def from_json(cls, json):
        return cls(
            round=json["round"],
            record_time=datetime.fromisoformat(json["record_time"]),
            issuance_per_sv_reward=DamlDecimal(json["issuance_per_sv_reward"]),
        )

@dataclass
class State:
    beneficiary: str
    # Maps CIDs of active SvRewardCoupons to their reward information
    active_rewards: dict[str, Reward]
    # # Maps CIDs of active issuing rounds to their round numbers
    # active_issuing_rounds_cid_to_round_number: dict[str, int]
    # Maps round numbers of active issuing rounds to their round information
    active_issuing_rounds: dict[int, IssuingRound]
    # # Maps CIDs of active closed rounds to their round numbers
    # active_closed_rounds_cid_to_round_number: dict[str, int]
    # Maps round numbers of active closed rounds to their round information
    active_closed_rounds: dict[int, ClosedRound]
    begin_record_time: datetime
    end_record_time: datetime
    grace_period_for_mining_rounds_in_minutes: int
    create_sv_reward_end_record_time: datetime
    pagination_key: PaginationKey
    weight: int
    already_minted_weight: int
    reward_summary: RewardSummary
    # Hash of relevant CLI arguments to validate cache compatibility across runs
    args_fingerprint: str
    # Stores SvRewardCoupon exercise events whose corresponding Create event has not
    # been seen by the worker. This happens when the Create occurred in a previous
    # chunk. These pending exercises are deferred until the global_state is updated
    # with the Create events from earlier chunks, at which point they can be
    # resolved.
    exercise_pending_events: list[tuple[TransactionTree, str]] # list[(transaction, event_id)]

    @classmethod
    def from_args(cls, args: argparse.Namespace):
        begin_record_time = datetime.fromisoformat(args.begin_record_time)
        grace_period_for_mining_rounds_in_minutes = args.grace_period_for_mining_rounds_in_minutes
        # A grace period is applied to ensure all relevant mining rounds are included,
        # especially for rewards created near the end of the specified time range.
        end_record_time = (
            datetime.fromisoformat(args.end_record_time)
            + timedelta(minutes=grace_period_for_mining_rounds_in_minutes)
        )
        pagination_key = PaginationKey(args.begin_migration_id, begin_record_time.isoformat())
        reward_summary = RewardSummary(
            reward_expired_count=0,
            reward_claimed_count=0,
            reward_expired_total_amount=DamlDecimal(0),
            reward_claimed_total_amount=DamlDecimal(0),
        )
        return cls(
            beneficiary=args.beneficiary,
            active_rewards={},
            active_issuing_rounds={},
            active_closed_rounds={},
            begin_record_time=begin_record_time,
            end_record_time=end_record_time,
            grace_period_for_mining_rounds_in_minutes = grace_period_for_mining_rounds_in_minutes,
            create_sv_reward_end_record_time = datetime.fromisoformat(args.end_record_time),
            pagination_key=pagination_key,
            weight = args.weight,
            already_minted_weight = args.already_minted_weight,
            reward_summary = reward_summary,
            args_fingerprint = _relevant_args_fingerprint(args),
            exercise_pending_events = [],
        )

    def to_json(self):
        return {
            "beneficiary": self.beneficiary,
            "active_rewards": {
                cid: reward.to_json()
                for cid, reward in self.active_rewards.items()
            },
            "active_issuing_rounds": {
                str(round_number): issuing_round.to_json()
                for round_number, issuing_round in self.active_issuing_rounds.items()
            },
            "active_closed_rounds": {
                str(round_number): closed_round.to_json()
                for round_number, closed_round in self.active_closed_rounds.items()
            },
            "begin_record_time": self.begin_record_time.isoformat(),
            "end_record_time": self.end_record_time.isoformat(),
            "grace_period_for_mining_rounds_in_minutes": self.grace_period_for_mining_rounds_in_minutes,
            "create_sv_reward_end_record_time": self.create_sv_reward_end_record_time.isoformat(),
            "pagination_key": self.pagination_key.to_json(),
            "weight": self.weight,
            "already_minted_weight": self.already_minted_weight,
            "reward_summary": self.reward_summary.to_json(),
            "args_fingerprint": self.args_fingerprint,
            "exercise_pending_events": [
                [tx.to_json(), event_id]
                for (tx, event_id) in self.exercise_pending_events
            ],
        }

    @classmethod
    def from_json(cls, data):
        return cls(
            beneficiary=data["beneficiary"],
            active_rewards={
                cid: Reward.from_json(reward_json)
                for cid, reward_json in data["active_rewards"].items()
            },
            active_issuing_rounds={
                int(round_number): IssuingRound.from_json(round_data)
                for round_number, round_data in data["active_issuing_rounds"].items()
            },
            active_closed_rounds={
                int(round_number): ClosedRound.from_json(round_data)
                for round_number, round_data in data["active_closed_rounds"].items()
            },
            begin_record_time=datetime.fromisoformat(data["begin_record_time"]),
            end_record_time=datetime.fromisoformat(data["end_record_time"]),
            grace_period_for_mining_rounds_in_minutes=data["grace_period_for_mining_rounds_in_minutes"],
            create_sv_reward_end_record_time=datetime.fromisoformat(data["create_sv_reward_end_record_time"]),
            pagination_key=PaginationKey.from_json(data["pagination_key"]),
            weight=int(data["weight"]),
            already_minted_weight=int(data["already_minted_weight"]),
            reward_summary=RewardSummary.from_json(data["reward_summary"]),
            args_fingerprint=data["args_fingerprint"],
            exercise_pending_events=[
                (TransactionTree.parse(tx_json), event_id)
                for (tx_json, event_id) in data["exercise_pending_events"]
            ],
        )

    def merge_from(self, worker_state):
        """
        Merge worker_state into this global state.
        exercise_pending_events are NOT merged here (they are processed then discarded).
        """

        # Merge rewards
        self.active_rewards.update(worker_state.active_rewards)

        # Merge issuing rounds
        self.active_issuing_rounds.update(worker_state.active_issuing_rounds)

        # Merge closed rounds
        self.active_closed_rounds.update(worker_state.active_closed_rounds)

        # Merge reward summary
        self.reward_summary.reward_claimed_count += worker_state.reward_summary.reward_claimed_count
        self.reward_summary.reward_expired_count += worker_state.reward_summary.reward_expired_count
        self.reward_summary.reward_claimed_total_amount += worker_state.reward_summary.reward_claimed_total_amount
        self.reward_summary.reward_expired_total_amount += worker_state.reward_summary.reward_expired_total_amount

    def should_process(self, tx: dict):
        return datetime.fromisoformat(tx["record_time"]) < self.end_record_time

    def process_transaction(self, tx: TransactionTree):
        self.process_events(tx, tx.root_event_ids)

    def process_events(self, transaction: TransactionTree, event_ids: list[str]):
        for event_id in event_ids:
            event = transaction.events_by_id[event_id]
            if isinstance(event, CreatedEvent):
                self.process_created_event(transaction, event)
            elif isinstance(event, ExercisedEvent):
                self.process_exercised_event(transaction, event)

    def process_created_event(self, transaction: TransactionTree, event: CreatedEvent):
        match event.template_id.qualified_name:
            case TemplateQualifiedNames.sv_reward_coupon:
                reward = Reward(
                    round=event.payload.get_sv_reward_coupon_round(),
                    weight=event.payload.get_sv_reward_coupon_weight(),
                    sv=event.payload.get_sv_reward_coupon_sv(),
                    beneficiary=event.payload.get_sv_reward_coupon_beneficiary(),
                    contract_id=event.contract_id,
                )
                if (
                    reward.beneficiary == self.beneficiary
                    and transaction.record_time <= self.create_sv_reward_end_record_time
                ):
                    LOG.debug(f"Adding reward {reward} to active_rewards")
                    self.active_rewards[event.contract_id] = reward
                elif reward.beneficiary == self.beneficiary:
                    LOG.debug(
                        f"Ignoring {reward} since record_time >= {self.create_sv_reward_end_record_time}"
                    )
            case TemplateQualifiedNames.issuing_mining_round:
                round_number = event.payload.get_issuing_mining_round_round()
                issuing_round = IssuingRound(
                    round=round_number,
                    record_time=transaction.record_time,
                    issuance_per_sv_reward=event.payload.get_issuing_mining_round_issuance_per_sv_reward(),
                )
                LOG.debug(f"Adding issuing round {issuing_round} to active_issuing_rounds")
                self.active_issuing_rounds[round_number] = issuing_round
            case TemplateQualifiedNames.closed_mining_round:
                round_number = event.payload.get_closed_mining_round_round()
                closed_round = ClosedRound(
                    round=round_number,
                    record_time=transaction.record_time,
                    issuance_per_sv_reward=event.payload.get_closed_mining_round_issuance_per_sv_reward(),
                )
                LOG.debug(f"Adding closed round {closed_round} to active_closed_rounds")
                self.active_closed_rounds[round_number] = closed_round


    def process_exercised_event(self, transaction: TransactionTree, event: ExercisedEvent):
        match event.template_id.qualified_name:
            case TemplateQualifiedNames.sv_reward_coupon:
                match event.choice_name:
                    case "SvRewardCoupon_DsoExpire":
                        self.handle_sv_reward_coupon_exercise(transaction, event, RewardStatus.EXPIRED)
                    case "SvRewardCoupon_ArchiveAsBeneficiary":
                        self.handle_sv_reward_coupon_exercise(transaction, event, RewardStatus.CLAIMED)
                    case choice if event.is_consuming:
                        self._fail(
                            f"Unexpected consuming choice {choice} on "
                            f"{TemplateQualifiedNames.sv_reward_coupon} — aborting."
                        )
                    case _:
                        self.process_events(transaction, event.child_event_ids)
            case _:
                self.process_events(transaction, event.child_event_ids)


    def handle_sv_reward_coupon_exercise(self, transaction, event, status):
        # Only handle SvRewardCoupons created within the configured time range for the given beneficiary
        match self.active_rewards.pop(event.contract_id, None):
            # None means either:
            # - The reward was created in the previous chunk, or
            # - The exercise event is for a different beneficiary
            case None:
                match status:
                    case RewardStatus.EXPIRED:
                        # Note: At this point it is not possible to filter by beneficiary.
                        self.exercise_pending_events.append((transaction.with_only_event(event), event.event_id))
                        LOG.debug(
                            "Queued DsoExpire event for deferred processing "
                            "(will be matched against active rewards from previous chunks)."
                        )
                    case RewardStatus.CLAIMED:
                        if self.beneficiary in event.acting_parties:
                            self.exercise_pending_events.append((transaction.with_only_event(event), event.event_id))
                            LOG.debug(
                                "Queued ArchiveAsBeneficiary event for deferred processing "
                                "(will be matched against active rewards from previous chunks)."
                            )
            case reward:
                match status:
                    case RewardStatus.EXPIRED:
                       # Consume and remove the closed round for this reward, since each closed round is used exactly once
                        match self.active_closed_rounds.pop(reward.round):
                            case None:
                                self.exercise_pending_events.append((transaction.with_only_event(event), event.event_id))
                                LOG.debug(
                                    "Queued DsoExpire event for deferred processing "
                                    "(will be matched against active closed round from previous chunks)."
                                )
                            case mining_round_info:
                                amount = self._calculate_amount(reward, mining_round_info)
                                LOG.debug(
                                    f"Updating expired summary with amount {amount}, corresponding to contract {event.contract_id}"
                                )
                                self.reward_summary.reward_expired_count += 1
                                self.reward_summary.reward_expired_total_amount += amount

                    case RewardStatus.CLAIMED:
                        # Consume and remove the issuing round for this reward, since each issuing round is used exactly once
                        match self.active_issuing_rounds.pop(reward.round):
                            case None:
                                self.exercise_pending_events.append((transaction.with_only_event(event), event.event_id))
                                LOG.debug(
                                    "Queued ArchiveAsBeneficiary event for deferred processing "
                                    "(will be matched against active issuing round from previous chunks)."
                                )
                            case mining_round_info:
                                amount = self._calculate_amount(reward, mining_round_info)
                                LOG.debug(
                                    f"Updating claimed summary with amount {amount}, corresponding to contract {event.contract_id}"
                                )
                                self.reward_summary.reward_claimed_count += 1
                                self.reward_summary.reward_claimed_total_amount += amount

        self.process_events(transaction, event.child_event_ids)

    def _calculate_amount(self, reward, mining_round_info):
        return self._calculate_weight(reward) * mining_round_info.issuance_per_sv_reward

    def _calculate_weight(self, reward):
        available_weight = max(0, reward.weight - self.already_minted_weight)
        if self.weight > available_weight:
            LOG.warning(
                f"Invalid weight input for round <{reward.round}>: "
                f"{self.weight} must be less than or equal to {available_weight}."
                f"The amount corresponding to {available_weight} will be computed."
            )
            return available_weight
        return self.weight


    def _fail_with_missing_round(self, reward):
        self._fail(
            f"Fatal: missing round {reward.round} for reward {reward.contract_id}\n"
            f"Consider increase input: grace-period-for-mining-rounds-in-minutes.\n"
            f"Currently it is set to {self.grace_period_for_mining_rounds_in_minutes}"
        )

    def _fail(self, message, cause=None):
        raise Exception(
            f"Stopping script (error: {message})"
        ) from cause


def _rename_to_backup(filename):
    """Rename FILENAME to a unique name in the same folder with leading and
    trailing `#`.

    We do this instead of using a tempfile for output and then renaming after
    because if tempfiles are on a different filesystem, the copy can fail in
    progress, which would destroy the old file if it was still in place."""
    target = os.path.join(os.path.dirname(filename), f"#{os.path.basename(filename)}")
    while True:
        target = f"{target}#"
        if not os.path.lexists(target):
            try:
                os.rename(filename, target)
                return target
            # lexists is 99%; deal with race conditions
            except FileExistsError:
                pass
            except IsADirectoryError:
                pass

def load_global_cache(args):
    """
    Strict-cache loader following the old behavior exactly:
    - If cache disabled → return fresh
    - If rebuild → fresh
    - If missing → fresh
    - If fingerprint mismatch → fresh
    - If any read/parse error → HARD FAIL (abort)
    """

    cache_file_path = args.cache_file_path

    # ---- Case 1: Caching disabled ----
    if cache_file_path is None:
        LOG.info("Caching disabled, creating new app state")
        state = State.from_args(args)
        return state, 0, int(args.begin_migration_id)

    # ---- Case 2: --rebuild-cache ----
    if args.rebuild_cache:
        LOG.info("Rebuilding cache, creating new app state")
        state = State.from_args(args)
        return state, 0, int(args.begin_migration_id)

    # ---- Case 3: Cache file does not exist ----
    if not os.path.exists(cache_file_path):
        LOG.info(f"File {cache_file_path} does not exist, creating new app state")
        state = State.from_args(args)
        return state, 0, int(args.begin_migration_id)

    # ---- Case 4: Try loading cache ----
    try:
        with open(cache_file_path, "r") as f:
            data = json.load(f)

        # fingerprint validation
        current_fp = _relevant_args_fingerprint(args)
        cached_fp = data.get("args_fingerprint")

        if cached_fp != current_fp:
            LOG.warning("Cached fingerprint mismatch, creating new app state")
            state = State.from_args(args)
            return state, 0, int(args.begin_migration_id)

        LOG.info(f"Restoring app state from {cache_file_path}")

        # Extract fields and restore
        state_json = data["state"]
        next_chunk = data["next_chunk_to_process"]
        current_mid = data["current_migration_id"]

        state = State.from_json(state_json)

        return state, next_chunk, current_mid

    except Exception as e:
        raise Exception(
            f"Stopping script (error: Could not read app state from {cache_file_path}: {e})"
        )

def save_global_cache(args, global_state, next_chunk_to_process, current_migration_id):
    cache_file_path = args.cache_file_path
    if not cache_file_path:
        return

    backup = None
    try:
        if os.path.exists(cache_file_path):
            backup = _rename_to_backup(cache_file_path)

        payload = {
            "state": global_state.to_json(),
            "next_chunk_to_process": next_chunk_to_process,
            "current_migration_id": current_migration_id,
            "args_fingerprint": global_state.args_fingerprint,
        }

        with open(cache_file_path, "w") as f:
            json.dump(payload, f)
            LOG.debug(f"Saved app state to {cache_file_path}")

    except Exception as e:
        LOG.error(f"Could not save app state to {cache_file_path}: {e}")
        if backup:
            os.replace(backup, cache_file_path)
            backup = None

    if backup:
        os.remove(backup)

async def run_chunk_serial(session, chunk_id, start_time, end_time, migration_id, args):
    """
    Executes the scan-processing loop for a specific time chunk.
    This function initializes a fresh State for the chunk, iterates through
    all available Scan API pages within the given time range, processes all
    transactions, and returns the resulting State.
    """

    begin_chunk = time.time()
    tx_count = 0

    # Initialize state for this worker chunk
    state = State.from_args(args)

    # Override time window for this chunk
    state.begin_record_time = start_time
    state.end_record_time = end_time

    # Set the initial pagination key for this chunk
    state.pagination_key = PaginationKey(migration_id, start_time.isoformat())

    # Rotate URLs for this chunk
    k = chunk_id % len(args.scan_urls)
    rotated_urls = args.scan_urls[k:] + args.scan_urls[:k]
    LOG.debug(f"Scan urls for chunk {chunk_id}: {rotated_urls}")

    scan_client = ScanClient(session, rotated_urls, args.page_size)

    while True:
        # Fetch a batch from Scan API
        LOG.debug(f"Fetching data from Scan API for chunk {chunk_id} with pagination_key: {state.pagination_key}")
        begin_batch = time.time()
        json_batch = await scan_client.updates(state.pagination_key)
        duration_batch = time.time() - begin_batch
        LOG.debug(f"End Scan API fetch for chunk {chunk_id} with pagination_key: {state.pagination_key}. ({duration_batch:.2f} sec.)")
        batch = [
            TransactionTree.parse(tx)
            for tx in json_batch
            if state.should_process(tx)
        ]

        # Detect migration_id changes and retry with an updated key
        if batch:
            maybe_new_mid = batch[0].migration_id
            if maybe_new_mid != state.pagination_key.last_migration_id:
                LOG.debug("migration_id changed; updating pagination key and retrying")
                state.pagination_key = PaginationKey(
                    maybe_new_mid,
                    state.pagination_key.last_record_time
                )
                json_batch = await scan_client.updates(state.pagination_key)
                batch = [
                    TransactionTree.parse(tx)
                    for tx in json_batch
                    if state.should_process(tx)
                ]

        LOG.debug(f"Processing batch of size {len(batch)} for chunk {chunk_id} at {state.pagination_key}")

        # Process each transaction
        for transaction in batch:
            state.process_transaction(transaction)
            tx_count += 1

        # Update pagination key based on the last processed transaction
        if len(batch) >= 1:
            last = batch[-1]
            state.pagination_key = PaginationKey(
                last.migration_id,
                last.record_time.isoformat()
            )

        # Stop when fewer results than the page size are returned
        if len(batch) < scan_client.page_size:
            LOG.debug(f"Reached end of chunk at {state.pagination_key}")
            break

    duration_chunk = time.time() - begin_chunk
    LOG.info(
        f"End chunk run. ({duration_chunk:.2f} sec., {tx_count} transaction(s), {scan_client.call_count} Scan API call(s),"
        f"{scan_client.retry_count} retries) for chunk {chunk_id}"
    )
    # The fully processed state of this chunk is returned
    return state


async def run_worker(session, chunk_id, start_time, end_time, migration_id, args):
    """
    Worker wrapper:
    - Executes run_chunk_serial()
    - Returns (chunk_id, resulting_state)
    """
    LOG.debug(
        f"Worker {chunk_id} STARTED [{start_time} → {end_time}] "
        f"using migration_id={migration_id}"
    )
    try:
        state = await run_chunk_serial(session, chunk_id, start_time, end_time, migration_id, args)
        return chunk_id, state
    except Exception as e:
        LOG.error(f"Fatal error in worker {chunk_id}: {e}")
        sys.exit(1)

def split_into_chunks(begin_time: datetime, end_time: datetime, chunk_size: timedelta):
    """
    Splits the full time interval into consecutive chunks of size `chunk_size`.
    """
    chunks = []
    chunk_id = 0

    current = begin_time
    while current < end_time:
        next_time = min(current + chunk_size, end_time)
        chunks.append((chunk_id, current, next_time))
        chunk_id += 1
        current = next_time

    return chunks


async def main():
    args = _parse_cli_args()

    # Set up logging
    LOG.setLevel(args.loglevel.upper())
    _log_uncaught_exceptions()

    LOG.info(f"Starting unclaimed_sv_rewards with arguments: {args}")
    LOG.info(f"Using scan URLs (round-robin): {args.scan_urls}")

    # Prepare initial parameters
    begin_time = datetime.fromisoformat(args.begin_record_time)
    end_time = datetime.fromisoformat(args.end_record_time) + timedelta(minutes=args.grace_period_for_mining_rounds_in_minutes)

    chunk_size = timedelta(hours=args.chunk_size_in_hours)

     # Generate chunks
    chunks = split_into_chunks(begin_time, end_time, chunk_size)
    num_chunks = len(chunks)
    LOG.info(f"Generated {num_chunks} chunk(s)")

    # -----------------
    # ASYNC STRUCTURES
    # -----------------

    concurrency = args.concurrency

    # chunk_id -> asyncio.Task
    tasks: dict[int, asyncio.Task] = {}

    # chunk_id -> State (result)
    results: dict[int, State] = {}

    # Load state or create fresh one
    global_state, next_chunk_to_process, current_migration_id = load_global_cache(args)

    # How many chunks have been launched already
    launched_chunks = next_chunk_to_process

    start_wall_clock = time.time()

    async with aiohttp.ClientSession() as shared_session:

        # MAIN SCHEDULER LOOP
        while tasks or launched_chunks < num_chunks:

            # Launch workers if capacity available
            while (
                len(tasks) < concurrency
                and launched_chunks < num_chunks
            ):
                chunk_id, start_time, end_time = chunks[launched_chunks]
                tasks[chunk_id] = asyncio.create_task(
                    run_worker(
                        shared_session,
                        chunk_id,
                        start_time,
                        end_time,
                        current_migration_id,
                        args
                    )
                )
                launched_chunks += 1

            # Wait for ANY worker to finish
            LOG.debug(f"Waiting for any worker to finish fetching/processing")
            done_set, _ = await asyncio.wait(
                tasks.values(),
                return_when=asyncio.FIRST_COMPLETED
            )
            done_task = next(iter(done_set))
            finished_chunk_id, worker_state = done_task.result()
            LOG.debug(f"Worker {finished_chunk_id} COMPLETE")
            del tasks[finished_chunk_id]
            # Store worker result for ordered processing
            results[finished_chunk_id] = worker_state

            # Check migration_id update
            worker_migration_id = worker_state.pagination_key.last_migration_id
            if worker_migration_id > current_migration_id:
                LOG.warning(
                    f"Migration changed: {current_migration_id} → {worker_migration_id}"
                )
                current_migration_id = worker_migration_id

            # Process chunks in order
            while next_chunk_to_process in results:
                # Merge the worker state's accumulated data
                LOG.debug(f"Merging chunk {next_chunk_to_process}")
                state = results.pop(next_chunk_to_process)
                global_state.merge_from(state)

                # Process exercise pending events (except chunk 0)
                if next_chunk_to_process != 0:
                    LOG.debug(
                        f"Re-procressing exercise pending events from chunk {next_chunk_to_process} "
                        f"over the global statte"
                        )
                    for (tx, event_id) in state.exercise_pending_events:
                        global_state.process_events(tx, [event_id])

                    # Drop unresolved pending events — after the global merge they are known to be
                    # irrelevant (cannot be attributed to the beneficiary).
                    global_state.exercise_pending_events.clear()

                next_chunk_to_process += 1
                save_global_cache(args, global_state, next_chunk_to_process, current_migration_id)

    # All chunks processed
    duration = time.time() - start_wall_clock
    LOG.info(f"All chunks complete ({duration:.2f}s)")

    assert not global_state.active_rewards, (
        "Some rewards remain unclaimed. The provided grace-period-for-mining-rounds-in-minutes "
        "might be too short to include all relevant mining rounds."
    )

    reward_summary = global_state.reward_summary
    LOG.info(f"reward_expired_count = {reward_summary.reward_expired_count}")
    LOG.info(f"reward_expired_total_amount = {reward_summary.reward_expired_total_amount.decimal:.10f}")
    LOG.info(f"reward_claimed_count = {reward_summary.reward_claimed_count}")
    LOG.info(f"reward_claimed_total_amount = {reward_summary.reward_claimed_total_amount.decimal:.10f}")
    LOG.info(f"reward_unclaimed_count = {len(global_state.active_rewards)}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        LOG.error(f"{e}")
        sys.exit(1)
