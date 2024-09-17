#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""
Given a time period and an SV, reports information about missed minting rights
"""

import aiohttp
import asyncio
import argparse
from decimal import *
from dataclasses import dataclass
from datetime import datetime, timedelta
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
        description="""Collects information about missed minting rights.

        Given two record times "begin-record-time" and "end-record-time", the script will
        find all rounds that were open (in any state???) at any time between [begin-record-time, end-record-time],
        and report for each of those rounds the amount of coin minted as SV rewards, as well as
        the amount of coin that was missed due to unclaimed minting rights.
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
        "--verbose",
        action="store_true",
        help="Display extra information on Amulet balances.",
    )
    parser.add_argument(
        "--party",
        help="Restrict the report to the given party.",
    )
    parser.add_argument(
        "--report-output",
        help="The name of a file to which a CSV report stream should be written.",
    )
    parser.add_argument(
        "--begin-migration-id",
        help="The migration id that was active at begin-record-time.",
        required=True,
    )
    parser.add_argument(
        "--begin-record-time",
        help="Start record time, see top level description. Expected in ISO format.",
        required=True,
    )
    parser.add_argument(
        "--end-record-time",
        help="End record time, see top level description. Expected in ISO format.",
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
    amulet = "Splice.Amulet:Amulet"
    locked_amulet = "Splice.Amulet:LockedAmulet"
    app_reward_coupon = "Splice.Amulet:AppRewardCoupon"
    validator_reward_coupon = "Splice.Amulet:ValidatorRewardCoupon"
    sv_reward_coupon = "Splice.Amulet:SvRewardCoupon"
    validator_faucet_coupon = "Splice.ValidatorLicense:ValidatorFaucetCoupon"
    validator_activity_record = "Splice.ValidatorLicense:ValidatorLivenessActivityRecord"
    open_mining_round = "Splice.Round:OpenMiningRound"
    summarizing_mining_round = "Splice.Round:SummarizingMiningRound"
    issuing_mining_round = "Splice.Round:IssuingMiningRound"
    closed_mining_round = "Splice.Round:ClosedMiningRound"
    dso_rules = "Splice.DSO:DSORules"


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

    async def get_acs_snapshot_page_at(self, migration_id, record_time, templates, after):
        payload = {
            "migration_id": migration_id,
            "record_time": record_time.isoformat(),
            "page_size": 1000,
            "templates": list(templates)
        }
        if after:
            payload["after"] = after

        response = await self.session.post(
            f"{self.url}/api/scan/v0/state/acs",
            json=payload,
        )
        response.raise_for_status()
        json = await response.json()
        return json

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

    # template ValidatorFaucetCoupon -> validator
    def get_validator_faucet_validator(self):
        return self.__get_record_field("validator").__get_party()

    # template ValidatorFaucetCoupon -> round
    def get_validator_faucet_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template ValidatorLivenessActivityRecord -> validator
    def get_validator_liveness_activity_record_validator(self):
        return self.__get_record_field("validator").__get_party()

    # template ValidatorLivenessActivityRecord -> round
    def get_validator_liveness_activity_record_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template ValidatorRewardCoupon -> user
    def get_validator_reward_user(self):
        return self.__get_record_field("user").__get_party()

    # template ValidatorRewardCoupon -> round
    def get_validator_reward_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template ValidatorRewardCoupon -> amount
    def get_validator_reward_amount(self):
        return self.__get_record_field("amount").__get_numeric()

    # template AppRewardCoupon -> provider
    def get_app_reward_provider(self):
        return self.__get_record_field("provider").__get_party()

    # template AppRewardCoupon -> featured
    def get_app_reward_featured(self):
        return self.__get_record_field("featured").__get_bool()

    # template AppRewardCoupon -> amount
    def get_app_reward_amount(self):
        return self.__get_record_field("amount").__get_numeric()

    # template AppRewardCoupon -> round
    def get_app_reward_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template OpenMiningRound -> round
    def get_open_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template ClosedMiningRound -> round
    def get_closed_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template IssuingMiningRound -> round
    def get_issuing_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template IssuingMiningRound -> issuancePerSvRewardCoupon
    def get_issuing_mining_round_issuance_per_sv_reward(self):
        return self.__get_record_field("issuancePerSvRewardCoupon").__get_numeric()

    # template IssuingMiningRound -> issuancePerValidatorRewardCoupon
    def get_issuing_mining_round_issuance_per_validator_reward(self):
        return self.__get_record_field(
            "issuancePerValidatorRewardCoupon"
        ).__get_numeric()

    # template IssuingMiningRound -> issuancePerFeaturedAppRewardCoupon
    def get_issuing_mining_round_issuance_per_featured_app_reward(self):
        return self.__get_record_field(
            "issuancePerFeaturedAppRewardCoupon"
        ).__get_numeric()

    # template IssuingMiningRound -> issuancePerUnfeaturedAppRewardCoupon
    def get_issuing_mining_round_issuance_per_unfeatured_app_reward(self):
        return self.__get_record_field(
            "issuancePerUnfeaturedAppRewardCoupon"
        ).__get_numeric()

    # template IssuingMiningRound -> optIssuancePerValidatorFaucetCoupon
    def get_issuing_mining_round_issuance_per_validator_faucet(self):
        return self.__get_record_field(
            "optIssuancePerValidatorFaucetCoupon"
        ).__get_numeric()

    # choice AmuletRules_Transfer -> transfer
    def get_amulet_rules_transfer_transfer(self):
        return self.__get_record_field("transfer")

    # data Transfer -> inputs
    def get_transfer_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # ExtTransferInput -> optInputValidatorFaucetCoupon
    def get_ext_transfer_input_validator_faucet_coupon(self) -> Optional[str]:
        cid = self.__get_record_field("optInputValidatorFaucetCoupon")
        if cid:
            return cid.get_contract_id()
        else:
            return None

    # template DSO -> svs
    def get_dso_rules_svs(self) -> dict[str, DamlDecimal]:
        return self.__get_record_field("svs")

    # data SvInfo -> svRewardWeight
    def get_svinfo_weight(self) -> DamlDecimal:
        return self.__get_record_field("svRewardWeight").__get_numeric()

    # data SvInfo -> joinedAsOfRound -> round
    def get_svinfo_joined_as_of_round(self) -> int:
        return self.__get_record_field("joinedAsOfRound").__get_round_number()

    def __get_optional(self) -> Optional[Self]:
        if self.value:
            return self
        else:
            return None

    def __get_numeric(self) -> DamlDecimal:
        try:
            return DamlDecimal(self.value)
        except Exception as e:
            raise LfValueParseException(self, "numeric", e)

    def __get_text(self) -> str:
        if isinstance(self.value, str):
            return self.value
        else:
            raise LfValueParseException(
                self, "text", f"Expected string, got {type(self.value)}"
            )

    def __get_variant(self):
        try:
            tag = self.value["tag"]
            value = self.value["value"]
            return {"tag": tag, "value": LfValue(value)}
        except Exception as e:
            raise LfValueParseException(self, "variant", e)

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

    def __get_list(self) -> list[Self]:
        if isinstance(self.value, list):
            return [LfValue(x) for x in self.value]
        else:
            raise LfValueParseException(
                self, "list", f"Expected list, got {type(self.value)}"
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

    def __get_bool(self) -> bool:
        if isinstance(self.value, bool):
            return self.value
        else:
            raise LfValueParseException(
                self, "boolean", f"Expected boolean, got {type(self.value)}"
            )

    def __get_timestamp(self) -> datetime:
        try:
            return datetime.fromisoformat(self.value)
        except Exception as e:
            raise LfValueParseException(self, "timestamp", e)


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
    reward_count: int
    amount_claimed: DamlDecimal
    amount_unclaimed: DamlDecimal
    amount_not_created: DamlDecimal

@dataclass
class Reward:
    round: int
    weight: DamlDecimal
    sv: str
    beneficiary: str
    contract_id: str
    claimed: bool

@dataclass
class IssuingRound:
    round: int
    record_time: datetime
    issuance_per_sv_reward: DamlDecimal

@dataclass
class SvInfo:
    weight: DamlDecimal
    joined_as_of_round: int

@dataclass
class DsoRules:
    svs: dict[str, SvInfo]

@dataclass
class State:
    party: str
    rewards: dict[str, Reward]
    issuing_rounds: dict[int, IssuingRound]
    open_rounds: list[int]
    closed_rounds: list[int]
    latest_dso_rules: Optional[DsoRules]
    guessed_intial_dso_rules: Optional[DsoRules]
    dso_rules_for_round: dict[int, DsoRules]
    begin_record_time: datetime
    end_record_time: datetime
    pagination_key: PaginationKey

    @classmethod
    def from_args(cls, args: argparse.Namespace):
        # We want to include events from the round that was active at begin_record_time,
        # so we need to go back the duration of around one round to ensure we include all events
        # that happened in that round.
        begin_record_time = datetime.fromisoformat(args.begin_record_time) - timedelta(minutes=25)
        end_record_time = datetime.fromisoformat(args.end_record_time) + timedelta(minutes=25)
        pagination_key = PaginationKey(args.begin_migration_id, begin_record_time.isoformat())
        return cls(
            party=args.party,
            rewards={},
            issuing_rounds={},
            open_rounds=[],
            closed_rounds=[],
            latest_dso_rules=None,
            guessed_intial_dso_rules=None,
            dso_rules_for_round={},
            begin_record_time=begin_record_time,
            end_record_time=end_record_time,
            pagination_key=pagination_key,
        )

    def all_parties(self) -> set[str]:
        return set([r.sv for r in self.rewards.values()])

    def all_rounds(self) -> list[IssuingRound]:
        """Returns the IssuingRound data for all rounds for which we have witnessed all events associated with that round"""
        all_rounds = list(self.issuing_rounds.keys())
        all_rounds.sort()
        rounds_with_data = [self.issuing_rounds[round] for round in all_rounds if round in self.open_rounds and round in self.closed_rounds]
        discarded = list(set(all_rounds) - set([r.round for r in rounds_with_data]))
        return rounds_with_data

    def summary(self, party: str) -> dict[int, RewardSummary]:
        result: dict[int, RewardSummary] = {}
        last_created_amount: Optional[DamlDecimal] = None
        for issuing_round in self.all_rounds():
            round = issuing_round.round

            amount_in_this_round = DamlDecimal(0)
            round_summary = RewardSummary(
                reward_count=0,
                amount_claimed=DamlDecimal(0),
                amount_unclaimed=DamlDecimal(0),
                amount_not_created=DamlDecimal(0),
            )
            for reward in self.rewards.values():
                if reward.round == round and reward.sv == party:
                    round_summary.reward_count = round_summary.reward_count + 1
                    reward_amount = reward.weight * issuing_round.issuance_per_sv_reward
                    amount_in_this_round = amount_in_this_round + reward_amount
                    if reward.claimed:
                        round_summary.amount_claimed = round_summary.amount_claimed + reward_amount
                    else:
                        round_summary.amount_unclaimed = round_summary.amount_unclaimed + reward_amount

            if round_summary.reward_count == 0:
                LOG.debug(f"No rewards created in round {round} for {party}")

                # Simple assumption: this SV should have created the same amount as in the last round where it created any rewards
                # This will break for two reasons:
                # 1. The amount of CC to issue per SV reward depends on how many actual SV, validator, and app rewards were created in the round
                # 2. The reward weight of SVs can change when DsoRules are updated
                round_summary.amount_not_created = last_created_amount
            else:
                last_created_amount = amount_in_this_round

            result[round] = round_summary
        return result

    def guess_initial_dso_rules(self):
        all_parties = self.all_parties()
        first_round = self.all_rounds()[0].round
        weight_by_sv = {}
        for reward in self.rewards.values():
            if reward.round == first_round:
                weight_by_sv.setdefault(reward.sv, DamlDecimal(0))
                weight_by_sv[reward.sv] = weight_by_sv[reward.sv] + reward.weight

        if len(weight_by_sv.keys()) < len(all_parties):
            raise Exception(
                f"Some SVs did not create rewards in the first round {first_round}. Cannot guess DSO rules. Run the script with a begin_record_time where all SVs were up."
            )
        self.guessed_intial_dso_rules = DsoRules(
            svs={ sv: SvInfo(weight=weight, joined_as_of_round=0) for sv, weight in weight_by_sv.items() }
        )

        LOG.warning(f"Using guessed initial DSO rules: {self.guessed_intial_dso_rules}")


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
        # LOG.debug(f"Processing create {event.template_id} -> {event.contract_id}")
        match event.template_id.qualified_name:
            case TemplateQualifiedNames.sv_reward_coupon:
                reward = Reward(
                    round=event.payload.get_sv_reward_coupon_round(),
                    weight=event.payload.get_sv_reward_coupon_weight(),
                    sv=event.payload.get_sv_reward_coupon_sv(),
                    beneficiary=event.payload.get_sv_reward_coupon_beneficiary(),
                    contract_id=event.contract_id,
                    claimed=False,
                )
                if self.party is None or reward.sv == self.party:
                    LOG.debug(f"Adding reward {reward} to rewards")
                    self.rewards[event.contract_id] = reward
            case TemplateQualifiedNames.issuing_mining_round:
                round_number = event.payload.get_issuing_mining_round_round()
                issuing_round = IssuingRound(
                    round=round_number,
                    record_time=transaction.record_time,
                    issuance_per_sv_reward=event.payload.get_issuing_mining_round_issuance_per_sv_reward(),
                )
                self.issuing_rounds[round_number] = issuing_round
                if self.latest_dso_rules:

                    self.dso_rules_for_round[round_number] = self.latest_dso_rules
                LOG.debug(f"Adding issuing round {issuing_round} to issuing_rounds")
            case TemplateQualifiedNames.open_mining_round:
                round_number = event.payload.get_open_mining_round_round()
                self.open_rounds.append(round_number)
                LOG.debug(f"Adding open round {round_number} to open_rounds")
            case TemplateQualifiedNames.closed_mining_round:
                round_number = event.payload.get_closed_mining_round_round()
                self.closed_rounds.append(round_number)
                LOG.debug(f"Adding closed round {round_number} to open_rounds")
            case TemplateQualifiedNames.dso_rules:
                self.process_dso_rules_created_event(event)

    def process_dso_rules_created_event(self, event):
        svs = event.payload.get_dso_rules_svs()
        svinfos = [SvInfo(
            weight = svs[party].get_svinfo_weight(),
            joined_as_of_round = svs[party].get_svinfo_joined_as_of_round(),
        ) for party in svs.keys()]
        self.latest_dso_rules = DsoRules(svs = svinfos)

    def process_exercised_event(self, transaction: TransactionTree, event: ExercisedEvent):
        # LOG.debug(f"Processing exercise {event.choice_name} on {event.contract_id}")
        match event.choice_name:
            case "AmuletRules_Transfer":
                self.handle_transfer(transaction, event)
            case _:
                self.process_events(transaction, event.child_event_ids)

    def claim_reward(self, contract_id: str):
        if contract_id in self.rewards:
            reward = self.rewards[contract_id]
            LOG.debug(f"Claiming reward {reward}")
            reward.claimed = True
        else:
            LOG.debug(f"Ignoring reward with unknown contract id {contract_id}")

    def handle_transfer(self, transaction, event):
        arg = event.exercise_argument.get_amulet_rules_transfer_transfer()
        inputs = arg.get_transfer_inputs()

        for i in inputs:
            tag: str = i["tag"]
            value: LfValue = i["value"]
            match tag:
                case "InputAppRewardCoupon":
                    # self.claim_reward(value.get_contract_id())
                    pass
                case "InputValidatorRewardCoupon":
                    # self.claim_reward(value.get_contract_id())
                    pass
                case "InputSvRewardCoupon":
                    self.claim_reward(value.get_contract_id())
                case "InputAmulet":
                    pass
                case "ExtTransferInput":
                    cid = value.get_ext_transfer_input_validator_faucet_coupon()
                    #if cid:
                    #    self.claim_reward(cid)
                    pass
                case "InputValidatorLivenessActivityRecord":
                    # self.claim_reward(value.get_contract_id())
                    pass
                case _:
                    self._fail(f"Unexpected transfer input: {tag}")

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

    LOG.debug(f"Starting missed_rounds with arguments: {args}")

    app_state: State = State.from_args(args)

    begin_t = time.time()
    tx_count = 0

    async with aiohttp.ClientSession() as session:
        scan_client = ScanClient(session, args.scan_url, args.page_size)

        # Get initial DsoRules (those might have been created long before the begin_record_time)
        # Note that MainNet currently does not implement the ACS endpoint
        try:
            snapshot_page = await scan_client.get_acs_snapshot_page_at(
                migration_id=app_state.pagination_key.last_migration_id,
                record_time=app_state.begin_record_time,
                templates=[TemplateQualifiedNames.dso_rules],
                after=None,
            )
            for event in snapshot_page["created_events"]:
                app_state.process_dso_rules_created_event(event)
        except Exception as e:
            LOG.debug(f"Failed to get DSO rules: {e}.")


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

    duration = time.time() - begin_t
    LOG.info(
        f"End run. ({duration:.2f} sec., {tx_count} transaction(s), {scan_client.call_count} Scan API call(s))"
    )

    all_rounds = app_state.all_rounds()
    all_parties = app_state.all_parties()
    LOG.debug(
        f"Total tracked: {len(app_state.issuing_rounds)} rounds, {len(all_parties)} parties, {len(app_state.rewards)} rewards"
    )

    LOG.info(
        f"Summary information for {len(all_rounds)} rounds between {all_rounds[0].record_time} and {all_rounds[-1].record_time}"
    )


    LOG.info(f"Totals by party:")
    for party in all_parties:
        short_party = party.split("::")[0]
        summary = app_state.summary(party = party)
        amount_claimed = sum([summary.amount_claimed for round, summary in summary.items()])
        amount_unclaimed = sum([summary.amount_unclaimed for round, summary in summary.items()])
        amount_not_created = sum([summary.amount_not_created for round, summary in summary.items()])
        rounds_claimed = [round for round, summary in summary.items() if summary.amount_claimed > DamlDecimal(0)]
        rounds_unclaimed = [round for round, summary in summary.items() if summary.amount_unclaimed > DamlDecimal(0)]
        rounds_not_created = [round for round, summary in summary.items() if summary.amount_not_created > DamlDecimal(0)]
        LOG.info(
            f"Party {short_party}: {amount_claimed}CC claimed in {len(rounds_claimed)} rounds, {amount_unclaimed}CC unclaimed in rounds {rounds_unclaimed}, {amount_not_created}CC not not created in rounds {rounds_not_created}"
        )


    if args.report_output:
        fieldnames = ["round", "time"]
        party_summaries = {}
        for party in all_parties:
            short_party = party.split("::")[0]
            fieldnames.append(short_party + " claimed")
            fieldnames.append(short_party + " missed")
            fieldnames.append(short_party + " not created")
            party_summaries[party] = app_state.summary(party = party)

        with CSVReport(args, fieldnames) as csv_report:
            for round in all_rounds:
                report_line = [round.round, round.record_time]
                for party in all_parties:

                    summary = party_summaries[party][round.round]
                    report_line.append(None if (summary.amount_claimed == DamlDecimal(0)) else summary.amount_claimed)
                    report_line.append(None if (summary.amount_unclaimed == DamlDecimal(0)) else summary.amount_unclaimed)
                    report_line.append(None if (summary.amount_not_created == DamlDecimal(0)) else summary.amount_not_created)
                csv_report.report_line(report_line)

if __name__ == "__main__":
    asyncio.run(main())
