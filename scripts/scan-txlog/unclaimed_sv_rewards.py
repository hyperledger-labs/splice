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
from decimal import *
from dataclasses import dataclass
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
    file_handler = logging.FileHandler("log/scan_txlog.log")
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
        default=100,
        help="Number of transactions to fetch per network request",
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
            f"{self._get_current_url()}/api/scan/v0/updates",
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
        self.call_count = self.call_count + 1
        total_attempts = max_retries * len(self.urls)

        while retry < total_attempts:
            try:
                response = await self.session.post(url, json=payload)
                if response.status in statuses:
                    LOG.debug(
                        f"Request to {url} with payload {payload} failed with status {response.status}"
                    )
                    retry += 1
                    if retry < total_attempts:
                        self.retry_count = self.retry_count + 1
                        self._rotate_to_next_url()
                        url = f"{self._get_current_url()}/api/scan/v0/updates"
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
                    self.retry_count = self.retry_count + 1
                    self._rotate_to_next_url()
                    url = f"{self._get_current_url()}/api/scan/v0/updates"
                    await asyncio.sleep(delay_seconds)
                else:
                    raise

        LOG.error(f"Exceeded max retries {total_attempts} across all URLs, giving up")
        response.raise_for_status()
        return await response.json()


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
        template_id = TemplateId(json["template_id"])
        contract_id = json["contract_id"]
        if "create_arguments" in json:
            return CreatedEvent(
                template_id, contract_id, LfValue(json["create_arguments"])
            )
        else:
            return ExercisedEvent(
                template_id,
                json["choice"],
                contract_id,
                LfValue(json["choice_argument"]),
                LfValue(json["exercise_result"]),
                json["child_event_ids"],
                json["consuming"],
            )

@dataclass
class ExercisedEvent(Event):
    template_id: TemplateId
    choice_name: str
    contract_id: str
    exercise_argument: LfValue
    exercise_result: LfValue
    child_event_ids: list
    is_consuming: bool


@dataclass
class CreatedEvent(Event):
    template_id: TemplateId
    contract_id: str
    payload: LfValue

    @classmethod
    def from_json(cls, json):
        return cls(
            TemplateId(json["template_id"]),
            json["contract_id"],
            LfValue(json["create_arguments"]),
        )

    def to_json(self):
        return {
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
    # Maps CIDs of active issuing rounds to their round numbers
    active_issuing_rounds_cid_to_round_number: dict[str, int]
    # Maps round numbers of active issuing rounds to their round information
    active_issuing_rounds: dict[int, IssuingRound]
    # Maps CIDs of active closed rounds to their round numbers
    active_closed_rounds_cid_to_round_number: dict[str, int]
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

    @classmethod
    def create_or_restore_from_cache(cls, args):
        if args.cache_file_path is None:
            LOG.info(f"Caching disabled, creating new app state")
            return cls.from_args(args)

        if args.rebuild_cache:
            LOG.info(f"Rebuilding cache, creating new app state")
            return cls.from_args(args)

        if not os.path.exists(args.cache_file_path):
            LOG.info(
                f"File {args.cache_file_path} does not exist, creating new app state"
            )
            return cls.from_args(args)

        try:
            with open(args.cache_file_path, "r") as file:
                data = json.load(file)

            current_fingerprint = _relevant_args_fingerprint(args)
            cached_fingerprint = data.get("args_fingerprint")
            if cached_fingerprint != current_fingerprint:
                LOG.warning("Cached fingerprint mismatch, creating new app state")
                return cls.from_args(args)

            LOG.info(f"Restoring app state from {args.cache_file_path}")
            return cls.from_json(data)

        except Exception as e:
            cls._fail(f"Could not read app state from {args.cache_file_path}: {e}")

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
            active_issuing_rounds_cid_to_round_number={},
            active_closed_rounds={},
            active_closed_rounds_cid_to_round_number={},
            begin_record_time=begin_record_time,
            end_record_time=end_record_time,
            grace_period_for_mining_rounds_in_minutes = grace_period_for_mining_rounds_in_minutes,
            create_sv_reward_end_record_time = datetime.fromisoformat(args.end_record_time),
            pagination_key=pagination_key,
            weight = args.weight,
            already_minted_weight = args.already_minted_weight,
            reward_summary = reward_summary,
            args_fingerprint = _relevant_args_fingerprint(args),
        )

    def to_json(self):
        return {
            "beneficiary": self.beneficiary,
            "active_rewards": {
                cid: reward.to_json()
                for cid, reward in self.active_rewards.items()
            },
            "active_issuing_rounds_cid_to_round_number": self.active_issuing_rounds_cid_to_round_number,
            "active_issuing_rounds": {
                str(round_number): issuing_round.to_json()
                for round_number, issuing_round in self.active_issuing_rounds.items()
            },
            "active_closed_rounds_cid_to_round_number": self.active_closed_rounds_cid_to_round_number,
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
        }

    @classmethod
    def from_json(cls, data):
        return cls(
            beneficiary=data["beneficiary"],
            active_rewards={
                cid: Reward.from_json(reward_json)
                for cid, reward_json in data["active_rewards"].items()
            },
            active_issuing_rounds_cid_to_round_number=data["active_issuing_rounds_cid_to_round_number"],
            active_issuing_rounds={
                int(round_number): IssuingRound.from_json(round_data)
                for round_number, round_data in data["active_issuing_rounds"].items()
            },
            active_closed_rounds_cid_to_round_number=data["active_closed_rounds_cid_to_round_number"],
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
        )

    def _save_to_cache(self, args):
        if args.cache_file_path:
            backup = None
            try:
                if os.path.exists(args.cache_file_path):
                    backup = _rename_to_backup(args.cache_file_path)
                with open(args.cache_file_path, "w") as file:
                    data = self.to_json()
                    json.dump(data, file)
                    LOG.debug(f"Saved app state to {args.cache_file_path}")
            except Exception as e:
                LOG.error(f"Could not save app state to {args.cache_file_path}: {e}")
                os.replace(backup, args.cache_file_path)  # overwrite if present
                backup = None
            if backup:
                os.remove(backup)

    def finalize_batch(self, args):
        self._save_to_cache(args)

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
                self.active_issuing_rounds_cid_to_round_number[event.contract_id] = round_number
                self.active_issuing_rounds[round_number] = issuing_round
            case TemplateQualifiedNames.closed_mining_round:
                round_number = event.payload.get_closed_mining_round_round()
                closed_round = ClosedRound(
                    round=round_number,
                    record_time=transaction.record_time,
                    issuance_per_sv_reward=event.payload.get_closed_mining_round_issuance_per_sv_reward(),
                )
                LOG.debug(f"Adding closed round {closed_round} to active_closed_rounds")
                self.active_closed_rounds_cid_to_round_number[event.contract_id] = round_number
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
            case TemplateQualifiedNames.issuing_mining_round:
                match event.choice_name:
                    case "Archive":
                        match self.active_issuing_rounds_cid_to_round_number.pop(event.contract_id, None):
                            case None:
                                pass
                            case round_number:
                                self.active_issuing_rounds.pop(round_number)
                    case _:
                        self.process_events(transaction, event.child_event_ids)
            case TemplateQualifiedNames.closed_mining_round:
                match event.choice_name:
                    case "Archive":
                        match self.active_closed_rounds_cid_to_round_number.pop(event.contract_id, None):
                            case None:
                                pass
                            case round_number:
                                self.active_closed_rounds.pop(round_number)
                    case _:
                        self.process_events(transaction, event.child_event_ids)
            case _:
                self.process_events(transaction, event.child_event_ids)


    def handle_sv_reward_coupon_exercise(self, transaction, event, status):
        # Only handle SvRewardCoupons created within the configured time range for the given beneficiary
        match self.active_rewards.pop(event.contract_id, None):
            # None means either:
            # - The reward was created outside the time range, or
            # - The exercise event is for a different beneficiary
            case None:
                pass
            case reward:
                match status:
                    case RewardStatus.EXPIRED:
                        match self.active_closed_rounds.get(reward.round):
                            case None:
                                self._fail_with_missing_round(reward)
                            case mining_round_info:
                                amount = self._calculate_amount(reward, mining_round_info)
                                LOG.debug(
                                    f"Updating expired summary with amount {amount}, corresponding to contract {event.contract_id}"
                                )
                                self.reward_summary.reward_expired_count += 1
                                self.reward_summary.reward_expired_total_amount += amount
                    case RewardStatus.CLAIMED:
                        match self.active_issuing_rounds.get(reward.round):
                            case None:
                                self._fail_with_missing_round(reward)
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


async def main():
    args = _parse_cli_args()

    # Set up logging
    LOG.setLevel(args.loglevel.upper())
    _log_uncaught_exceptions()

    LOG.info(f"Starting unclaimed_sv_rewards with arguments: {args}")
    LOG.info(f"Using scan URLs (round-robin): {args.scan_urls}")

    app_state = State.create_or_restore_from_cache(args)

    begin_t = time.time()
    tx_count = 0

    async with aiohttp.ClientSession() as session:
        scan_client = ScanClient(session, args.scan_urls, args.page_size)

        while True:
            json_batch = await scan_client.updates(app_state.pagination_key)
            batch = [TransactionTree.parse(tx) for tx in json_batch if app_state.should_process(tx)]
            LOG.debug(
                f"Processing batch of size {len(batch)} starting at {app_state.pagination_key}"
            )
            for transaction in batch:
                app_state.process_transaction(transaction)
                tx_count = tx_count + 1

            if len(batch) >= 1:
                last = batch[-1]
                app_state.pagination_key = PaginationKey(
                    last.migration_id, last.record_time.isoformat()
                )
                app_state.finalize_batch(args)
            if len(batch) < scan_client.page_size:
                LOG.debug(f"Reached end of stream at {app_state.pagination_key}")
                break

    duration = time.time() - begin_t
    LOG.info(
        f"End run. ({duration:.2f} sec., {tx_count} transaction(s), {scan_client.call_count} Scan API call(s), {scan_client.retry_count} retries)"
    )
    LOG.debug(
        f"active_mining_rounds count: {len(app_state.active_issuing_rounds)}"
    )
    LOG.debug(
        f"active_closed_rounds count: {len(app_state.active_closed_rounds)}"
    )

    assert not app_state.active_rewards, (
        "Some rewards remain unclaimed. The provided grace-period-for-mining-rounds-in-minutes "
        "might be too short to include all relevant mining rounds."
    )

    reward_summary = app_state.reward_summary

    LOG.info(f"reward_expired_count = {reward_summary.reward_expired_count}")
    LOG.info(f"reward_expired_total_amount = {reward_summary.reward_expired_total_amount.decimal:.10f}")
    LOG.info(f"reward_claimed_count = {reward_summary.reward_claimed_count}")
    LOG.info(f"reward_claimed_total_amount = {reward_summary.reward_claimed_total_amount.decimal:.10f}")
    LOG.info(f"reward_unclaimed_count = {len(app_state.active_rewards)}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        LOG.error(f"{e}")
        sys.exit(1)
