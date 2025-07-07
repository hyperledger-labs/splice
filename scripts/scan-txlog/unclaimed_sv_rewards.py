#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Summarizes claimed, expired, and unclaimed minting rewards for a given beneficiary
within a specified time range, based on SvRewardCoupon activity.
"""

import aiohttp
import asyncio
import argparse
from decimal import *
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
import logging
import colorlog
from typing import Optional, TextIO, Self, Any
import csv
import time
import sys

# Set precision and rounding mode
getcontext().prec = 38
getcontext().rounding = ROUND_HALF_EVEN

def _default_logger(name, loglevel):
    cli_handler = colorlog.StreamHandler()
    cli_handler.setFormatter(
        colorlog.ColoredFormatter(
            "%(log_color)s%(levelname)s:%(name)s:%(message)s",
            log_colors={
                "DEBUG": "cyan",
                "INFO": "green",
                "WARNING": "yellow",
                "ERROR": "red",
                "CRITICAL": "red,bg_white",
            },
        )
    )
    file_handler = logging.FileHandler("log/scan_txlog.log")
    file_handler.setFormatter(logging.Formatter("%(levelname)s:%(name)s:%(message)s"))

    logger = colorlog.getLogger(name)
    logger.addHandler(cli_handler)
    logger.addHandler(file_handler)
    logger.setLevel(loglevel)

    return logger


# Global logger, always accessible
LOG = _default_logger("global", "INFO")

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
        "scan_url",
        help="Address of the Splice Scan server",
        default="http://localhost:5012",
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
        "--report-output",
        help="The name of a file to which a CSV report should be written.",
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
        help="Start of the record time range to consider SvRewardCoupon creation. Expected in ISO format: 2025-07-01T10:30:00Z.",
        required=True,
    )
    parser.add_argument(
        "--end-record-time",
        help="End of the record time range to consider SvRewardCoupon creation. Expected in ISO format: 2025-07-01T12:30:00Z",
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
    url: str
    page_size: int
    call_count: int = 0

    async def updates(self, after: Optional[PaginationKey]):
        self.call_count = self.call_count + 1
        payload = {"page_size": self.page_size}
        if after:
            payload["after"] = after.to_json()
        response = await self.session.post(
            f"{self.url}/api/scan/v0/updates", json=payload
        )
        response.raise_for_status()
        json = await response.json()
        return json["transactions"]


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

@dataclass
class RewardSummary:
    reward_expired_count: int
    reward_claimed_count: int
    reward_unclaimed_count: int
    reward_expired_total_amount: DamlDecimal
    reward_claimed_total_amount: DamlDecimal
    reward_unclaimed_total_ammount: DamlDecimal

class RewardStatus(Enum):
    UNCLAIMED = "unclaimed"
    CLAIMED = "claimed"
    EXPIRED = "expired"

@dataclass
class Reward:
    round: int
    weight: DamlDecimal
    sv: str
    beneficiary: str
    contract_id: str
    status: RewardStatus = field(default=RewardStatus.UNCLAIMED)

@dataclass
class IssuingRound:
    round: int
    record_time: datetime
    issuance_per_sv_reward: DamlDecimal

@dataclass
class State:
    beneficiary: str
    rewards: dict[str, Reward]
    issuing_rounds: dict[int, IssuingRound]
    begin_record_time: datetime
    end_record_time: datetime
    grace_period_for_mining_rounds_in_minutes: datetime
    create_sv_reward_end_record_time: datetime
    pagination_key: PaginationKey

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
        return cls(
            beneficiary=args.beneficiary,
            rewards={},
            issuing_rounds={},
            begin_record_time=begin_record_time,
            end_record_time=end_record_time,
            grace_period_for_mining_rounds_in_minutes = grace_period_for_mining_rounds_in_minutes,
            create_sv_reward_end_record_time = datetime.fromisoformat(args.end_record_time),
            pagination_key=pagination_key,
        )

    def summary(self) -> RewardSummary:
        reward_summary = RewardSummary(
            reward_expired_count=0,
            reward_claimed_count=0,
            reward_unclaimed_count=0,
            reward_expired_total_amount=DamlDecimal(0),
            reward_claimed_total_amount=DamlDecimal(0),
            reward_unclaimed_total_ammount=DamlDecimal(0),
        )
        for reward in self.rewards.values():
            mining_round_info = self.issuing_rounds.get(reward.round)
            # If the mining round for a reward is not found, it means that a `SvRewardCoupon` was
            # created, but the corresponding mining round was created after the end-record-time + grace period.
            # This should not happen, as the additional grace period should be sufficient to ensure
            # we capture the complete set of mining rounds for the collected rewards.
            if not mining_round_info:
                LOG.error(
                    f"Fatal: missing issuing round {reward.round} for reward {reward.contract_id}\n"
                    f"Consider increase input: grace-period-for-mining-rounds-in-minutes.\n"
                    f"Currently it is set to {self.grace_period_for_mining_rounds_in_minutes}"
                )
                raise RuntimeError("Issuing round not found — aborting.")

            amount = reward.weight * mining_round_info.issuance_per_sv_reward
            match reward.status:
                case RewardStatus.EXPIRED:
                    reward_summary.reward_expired_count += 1
                    reward_summary.reward_expired_total_amount += amount
                case RewardStatus.CLAIMED:
                    reward_summary.reward_claimed_count += 1
                    reward_summary.reward_claimed_total_amount += amount
                case RewardStatus.UNCLAIMED:
                    LOG.warning(
                        f"{reward} remains unclaimed, even after applying a grace period of "
                        f"{self.grace_period_for_mining_rounds_in_minutes} minutes."
                    )
                    reward_summary.reward_unclaimed_count += 1
                    reward_summary.reward_unclaimed_total_ammount += amount

        return reward_summary

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
                    LOG.debug(f"Adding reward {reward} to rewards")
                    self.rewards[event.contract_id] = reward
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
                self.issuing_rounds[round_number] = issuing_round
                LOG.debug(f"Adding issuing round {issuing_round} to issuing_rounds")

    def process_exercised_event(self, transaction: TransactionTree, event: ExercisedEvent):
        match event.choice_name:
            case "SvRewardCoupon_DsoExpire":
                self.handle_sv_reward_coupon_exercise(transaction, event, RewardStatus.EXPIRED)
            case "SvRewardCoupon_ArchiveAsBeneficiary":
                self.handle_sv_reward_coupon_exercise(transaction, event, RewardStatus.CLAIMED)
            case _:
                self.process_events(transaction, event.child_event_ids)

    def handle_sv_reward_coupon_exercise(self, transaction, event, status):
        contract_id = event.contract_id
        # Only handle SvRewardCoupons created within the configured time range for the given beneficiary
        match self.rewards.get(contract_id):
            # None means either:
            # - The reward was created outside the time range, or
            # - The exercise event is for a different beneficiary
            case None:
                pass
            case _:
                LOG.debug(f"Changing status {status} for: {event.contract_id}")
                self.rewards[contract_id].status = status

        self.process_events(transaction, event.child_event_ids)

    def _fail(self, message, cause=None):
        LOG.error(message)
        raise Exception(
            f"Stopping export (error: {message})"
        ) from cause


class CSVReport:
    filename: str
    fieldnames: list[str]
    csv_writer: Optional[csv.DictWriter]
    report_stream: any

    def __init__(self, args: argparse.Namespace, fieldnames: list[str]):
        self.filename = args.report_output
        self.fieldnames = fieldnames
        self.report_stream = None
        self.csv_writer = None

    # Called when entering a context block ("with ... as ...")
    def __enter__(self):
        self.report_stream = open(self.filename, "w", buffering=1)
        self.csv_writer = csv.writer(self.report_stream)
        self.csv_writer.writerow(self.fieldnames)
        return self

    # Called when exiting a context block, including when an exception is raised
    def __exit__(self, exc_type, exc_value, traceback):
        if self.report_stream:
            self.report_stream.close()

    def report_line(self, line_info: list[Any]):
        self.csv_writer.writerow(line_info)


async def main():
    args = _parse_cli_args()

    # Set up logging
    LOG.setLevel(args.loglevel.upper())
    _log_uncaught_exceptions()

    LOG.info(f"Starting unclaimed_sv_rewards with arguments: {args}")

    app_state: State = State.from_args(args)

    begin_t = time.time()
    tx_count = 0

    async with aiohttp.ClientSession() as session:
        scan_client = ScanClient(session, args.scan_url, args.page_size)

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
            if len(batch) < scan_client.page_size:
                LOG.debug(f"Reached end of stream at {app_state.pagination_key}")
                break

    LOG.debug(
        f"Total tracked: {len(app_state.issuing_rounds)} mining rounds, {len(app_state.rewards)} rewards"
    )

    summary = app_state.summary()

    duration = time.time() - begin_t
    LOG.info(
        f"End run. ({duration:.2f} sec., {tx_count} transaction(s), {scan_client.call_count} Scan API call(s))"
    )

    LOG.info(f"reward_expired_count = {summary.reward_expired_count}")
    LOG.info(f"reward_expired_total_amount = {summary.reward_expired_total_amount.decimal:.10f}")
    LOG.info(f"reward_claimed_count = {summary.reward_claimed_count}")
    LOG.info(f"reward_claimed_total_amount = {summary.reward_claimed_total_amount.decimal:.10f}")
    LOG.info(f"reward_unclaimed_count = {summary.reward_unclaimed_count}")
    LOG.info(f"reward_unclaimed_total_ammount = {summary.reward_unclaimed_total_ammount.decimal:.10f}")


    if args.report_output:
        fieldnames = [
            "reward_expired_count",
            "reward_expired_total_amount",
            "reward_claimed_count",
            "reward_claimed_total_amount",
            "reward_unclaimed_count",
            "reward_unclaimed_total_amount",
        ]

        with CSVReport(args, fieldnames) as csv_report:
            report_line = [
                summary.reward_expired_count,
                f"{summary.reward_expired_total_amount.decimal:.10f}",
                summary.reward_claimed_count,
                f"{summary.reward_claimed_total_amount.decimal:.10f}",
                summary.reward_unclaimed_count,
                f"{summary.reward_unclaimed_total_ammount.decimal:.10f}",
            ]
            csv_report.report_line(report_line)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except RuntimeError as e:
        LOG.error(f"{e}")
        sys.exit(1)
