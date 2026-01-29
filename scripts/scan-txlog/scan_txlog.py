#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import aiohttp
import asyncio
import colorlog
import csv
from dataclasses import dataclass
from decimal import *
from datetime import datetime
import json
import logging
import textwrap
from typing import Any, Dict, List, Optional, TextIO
import sys
import argparse
import os
import time


def FAIL(message):
    LOG.error(message)
    sys.exit(-1)


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

# Set precision and rounding mode
getcontext().prec = 38
getcontext().rounding = ROUND_HALF_EVEN


def _setup_logger(name, loglevel, file_path):
    # Ensure the log directory exists
    log_directory = os.path.dirname(file_path)
    if log_directory and not os.path.exists(log_directory):
        os.makedirs(log_directory)

    file_handler = logging.FileHandler(file_path)
    file_handler.setFormatter(
        logging.Formatter("%(levelname)s:%(name)s:%(asctime)s:%(message)s")
    )
    logger = colorlog.getLogger(name)
    logger.addHandler(cli_handler)
    logger.addHandler(file_handler)
    logger.setLevel(loglevel)

    return logger


def _party_enabled(args, p):
    return not args.party or args.party == p


class CSVReport:
    csv_writer: Optional[csv.DictWriter]
    current_batch: List[Dict[str, Any]]

    def __init__(self, report_stream, write_header):
        fieldnames = [
            "row_type",
            "update_id",
            "event_id",
            "record_time",
            "provider",
            "sender",
            "receiver",
            "input_reward_cc_total",
            "input_amulet_cc_total",
            "holding_fees_total",
            "tx_fees_total",
            "source",
            "round",
            "owner",
            "initial_amount_cc",
            "effective_amount_cc",
            "currency",
            "lock_holders",
            "cc_burnt",
            "traffic_bytes_bought",
            "traffic_member_id",
        ]

        self.current_batch = []

        if report_stream:
            self.csv_writer = csv.DictWriter(report_stream, fieldnames)

            if write_header:
                self.csv_writer.writeheader()
        else:
            self.csv_writer = None

    def report_line(self, row_type, line_info):
        if not self.csv_writer:
            return

        # Writes are deferred until the report is flushed. This is done to
        # ensure that a round can be fully processed and committed to cache
        # prior to generating report output. Without this safeguard, the
        # report process is not necessarily restartable after an error, due
        # to the possibilty partially written round in the output.
        self.current_batch.append({**line_info, "row_type": row_type})

    def flush(self):
        for row in self.current_batch:
            self.csv_writer.writerow(row)

        self.current_batch = []


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


class TemplateQualifiedNames:
    amulet = "Splice.Amulet:Amulet"
    locked_amulet = "Splice.Amulet:LockedAmulet"
    app_reward_coupon = "Splice.Amulet:AppRewardCoupon"
    validator_reward_coupon = "Splice.Amulet:ValidatorRewardCoupon"
    sv_reward_coupon = "Splice.Amulet:SvRewardCoupon"
    validator_faucet_coupon = "Splice.ValidatorLicense:ValidatorFaucetCoupon"
    validator_liveness_activity_record = (
        "Splice.ValidatorLicense:ValidatorLivenessActivityRecord"
    )
    open_mining_round = "Splice.Round:OpenMiningRound"
    summarizing_mining_round = "Splice.Round:SummarizingMiningRound"
    issuing_mining_round = "Splice.Round:IssuingMiningRound"
    ans_entry_context = "Splice.Ans:AnsEntryContext"
    subscription_idle_state = "Splice.Wallet.Subscriptions:SubscriptionIdleState"

    dso_rules = "Splice.DsoRules:DsoRules"
    dso_bootstrap = "Splice.DsoBootstrap:DsoBootstrap"
    amulet_rules = "Splice.AmuletRules:AmuletRules"
    unclaimed_reward = "Splice.Amulet:UnclaimedReward"
    amulet_conversion_rate_feed = (
        "Splice.Ans.AmuletConversionRateFeed:AmuletConversionRateFeed"
    )

    all_tracked = set(
        [
            amulet,
            locked_amulet,
            app_reward_coupon,
            validator_reward_coupon,
            sv_reward_coupon,
            validator_faucet_coupon,
            validator_liveness_activity_record,
            open_mining_round,
            summarizing_mining_round,
            issuing_mining_round,
            ans_entry_context,
            subscription_idle_state,
            unclaimed_reward,
        ]
    )

    all_tracked_with_package_name = set(
        [
            "splice-amulet:" + amulet,
            "splice-amulet:" + locked_amulet,
            "splice-amulet:" + app_reward_coupon,
            "splice-amulet:" + validator_reward_coupon,
            "splice-amulet:" + sv_reward_coupon,
            "splice-amulet:" + validator_faucet_coupon,
            "splice-amulet:" + validator_liveness_activity_record,
            "splice-amulet:" + open_mining_round,
            "splice-amulet:" + summarizing_mining_round,
            "splice-amulet:" + issuing_mining_round,
            "splice-amulet-name-service:" + ans_entry_context,
            "splice-wallet-payments:" + subscription_idle_state,
            "splice-amulet:" + unclaimed_reward,
        ]
    )


@dataclass
class HandleTransactionResult:
    for_open_round: str
    new_open_rounds: set
    new_closed_round: str

    @staticmethod
    def empty():
        return HandleTransactionResult(None, set(), None)

    def for_open_round(round_number):
        return HandleTransactionResult(round_number, set(), None)

    def new_open_rounds(rounds):
        return HandleTransactionResult(None, rounds, None)

    def new_open_round(round_number):
        return HandleTransactionResult(None, set([round_number]), None)

    def new_closed_round(new_closed_round):
        return HandleTransactionResult(None, set(), new_closed_round)

    def merge(self, other):
        # This is technically an unnecessary limitation but unlikely to be violated in practice.
        # To fix it the state should get updated through individual root events instead of handle_transaction.
        if (
            other.for_open_round
            and self.for_open_round
            and other.for_open_round != self.for_open_round
        ):
            raise Exception(
                f"Round mismatch: {other.for_open_round} != {self.for_open_round}"
            )

        return HandleTransactionResult(
            self.for_open_round or other.for_open_round,
            self.new_open_rounds.union(other.new_open_rounds),
            self.new_closed_round or other.new_closed_round,
        )


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
    retry_count: int = 0

    async def updates(self, after: Optional[PaginationKey]):
        self.call_count = self.call_count + 1
        payload = {"page_size": self.page_size}
        if after:
            payload["after"] = after.to_json()
        response = await self.session.post(
            f"{self.url}/api/scan/v1/updates", json=payload
        )
        response.raise_for_status()
        json = await response.json()
        return json["transactions"]

    async def __get_with_retry_on_statuses(
        self, url, params, max_retries, delay_seconds, statuses
    ):
        assert max_retries >= 1
        retry = 0
        self.call_count = self.call_count + 1
        while retry < max_retries:
            response = await self.session.get(url, params=params)
            if response.status in statuses:
                LOG.debug(
                    f"Request to {url} with params {params} failed with status {response.status}, retrying after {delay_seconds} seconds"
                )
                retry += 1
                if retry < max_retries:
                    self.retry_count = self.retry_count + 1
                await asyncio.sleep(delay_seconds)
            else:
                break
        if retry == max_retries:
            LOG.error(f"Exceeded max retries {max_retries}, giving up")
        response.raise_for_status()
        return await response.json()

    async def get_acs_snapshot_page_at(
        self, migration_id, record_time, templates, after
    ):
        payload = {
            "migration_id": migration_id,
            "record_time": record_time.isoformat(),
            "page_size": 1000,
            "templates": list(templates),
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

    async def get_amulet_token_metadata(self):
        response = await self.session.get(
            f"{self.url}/registry/metadata/v1/instruments/Amulet"
        )
        try:
            response.raise_for_status()
        except Exception as e:
            text = await response.text()
            LOG.error(f"Failed to get amulet token metadata: {e}, response: {text}")
            raise e

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
            try:
                self.decimal = decimal.quantize(
                    Decimal("0.0000000001"), rounding=ROUND_HALF_EVEN
                )
            except Exception as e:
                LOG.error(f"Failed to treat {decimal} as DamlDecimal: {e}")
                raise e

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


# Note: This only contains package ids we need to match against
# but does not attempt to be exhaustive.
class KnownPackageIds:
    amulet_0_1_0 = "48cac5ba4b6bf78df6c3a952ce05409a1d2ef39c05351074679adc0cf9cd1351"
    amulet_0_1_1 = "67bc95402ad7b08fcdff0ed478308d39c70baca2238c35c5d425a435a8a9e7f7"
    amulet_0_1_2 = "1446ffdf23326cef2de97923df96618eb615792bea36cf1431f03639448f1645"
    amulet_0_1_3 = "0d89016d5a90eb8bced48bbac99e81c57781b3a36094b8d48b8e4389851e19af"
    amulet_0_1_4 = "a36ef8888fb44caae13d96341ce1fabd84fc9e2e7b209bbc3caabb48b6be1668"
    amulet_0_1_5 = "b4867a47abbfa2d15482f22c9c50516f0af14036287f299342f5d336391e4997"
    amulet_0_1_6 = "979ec710c3ae3a05cb44edf8461a9b4d7dd2053add95664f94fc89e5f18df80f"
    amulet_0_1_7 = "4646d50cbdec6f088c98ae543da5c973d2d1be3363b9f32eb097d8fdc063ade7"
    amulet_0_1_8 = "511bd3bf23fab4e5171edb22dceabe3061f7faf78a44f8af44f3b87f977c61f6"
    amulet_0_1_9 = "a5b055492fb8f08b2e7bc0fc94da6da50c39c2e1d7f24cd5ea8db12fc87c1332"
    amulet_0_1_10 = "17e51d96dbe9e41e7e5f621861419905206c12aeef77956c0a0da14714c1fa62"
    amulet_0_1_11 = "9824927cdb455f833867b74c01cffcd8cb8cc5edd4d2273cea1329b708ef3ce5"
    amulet_0_1_12 = "95a88ff9ffd509e097802ecf3bbd58c83a5dff408e439cca4e2105ebd2cd0760"
    amulet_0_1_13 = "6e9fc50fb94e56751b49f09ba2dc84da53a9d7cff08115ebb4f6b7a12d0c990c"

    # Returns True if the package id of splice-amulet that the
    # AmuletRules_Transfer choice was exercised on
    # does not contain the fix from #12735.
    @staticmethod
    def has_broken_transfer_fee_computation(package_id):
        return package_id in [
            KnownPackageIds.amulet_0_1_0,
            KnownPackageIds.amulet_0_1_1,
        ]

    @staticmethod
    def deducts_holding_fees(package_id):
        return package_id in [
            KnownPackageIds.amulet_0_1_0,
            KnownPackageIds.amulet_0_1_1,
            KnownPackageIds.amulet_0_1_2,
            KnownPackageIds.amulet_0_1_3,
            KnownPackageIds.amulet_0_1_4,
            KnownPackageIds.amulet_0_1_5,
            KnownPackageIds.amulet_0_1_6,
            KnownPackageIds.amulet_0_1_7,
            KnownPackageIds.amulet_0_1_8,
            KnownPackageIds.amulet_0_1_9,
            KnownPackageIds.amulet_0_1_10,
            KnownPackageIds.amulet_0_1_11,
            KnownPackageIds.amulet_0_1_12,
            KnownPackageIds.amulet_0_1_13,
        ]


@dataclass
class SteppedRate:
    initial_rate: DamlDecimal
    steps: list

    def scale(self, factor):
        return SteppedRate(
            self.initial_rate,
            [(x * factor, y) for (x, y) in self.steps],
        )

    def get_steps_differences(self, package_id):
        if KnownPackageIds.has_broken_transfer_fee_computation(package_id):
            return self.steps
        else:
            steps_differences = []
            total = DamlDecimal(0)
            for step, stepped_rate in self.steps:
                steps_differences += [(step - total, stepped_rate)]
                total += step
            return steps_differences

    def charge(self, amount, package_id):
        steps_differences = self.get_steps_differences(package_id)
        remainder = amount
        total = DamlDecimal(0)
        steps_index = 0
        current_rate = self.initial_rate
        while remainder > DamlDecimal(0):
            if steps_index >= len(steps_differences):
                total += remainder * current_rate
                remainder = DamlDecimal(0)
            else:
                (step, stepped_rate) = steps_differences[steps_index]
                steps_index += 1
                total += min(remainder, step) * current_rate
                current_rate = stepped_rate
                remainder -= step
        return total


# TODO(DACH-NY/canton-network-node#12755) Replace these assertions by a real unit testing framework

stepped_rate_example = SteppedRate(
    DamlDecimal("0.1"),
    [
        (DamlDecimal("100.0"), DamlDecimal("0.001")),
        (DamlDecimal("1000.0"), DamlDecimal("0.0001")),
        (DamlDecimal("1000000.0"), DamlDecimal("0.00001")),
    ],
)

assert (
    stepped_rate_example.get_steps_differences(KnownPackageIds.amulet_0_1_0)
    == stepped_rate_example.steps
)

assert stepped_rate_example.get_steps_differences("unknown") == [
    (DamlDecimal("100.0"), DamlDecimal("0.001")),
    (DamlDecimal("900.0"), DamlDecimal("0.0001")),
    (DamlDecimal("998900.0"), DamlDecimal("0.00001")),
]

test_value = (
    DamlDecimal("100.0")
    + DamlDecimal("1000.0")
    + DamlDecimal("1000000.0")
    + DamlDecimal("1000000.0")
)

assert stepped_rate_example.charge(
    test_value, KnownPackageIds.amulet_0_1_0
) == DamlDecimal("100.0") * DamlDecimal("0.1") + DamlDecimal("1000.0") * DamlDecimal(
    "0.001"
) + DamlDecimal(
    "1000000.0"
) * DamlDecimal(
    "0.0001"
) + DamlDecimal(
    "1000000.0"
) * DamlDecimal(
    "0.00001"
)

assert stepped_rate_example.charge(test_value, "unknown") == DamlDecimal(
    "100.0"
) * DamlDecimal("0.1") + DamlDecimal("900.0") * DamlDecimal("0.001") + DamlDecimal(
    "998900.0"
) * DamlDecimal(
    "0.0001"
) + DamlDecimal(
    "1001200.0"
) * DamlDecimal(
    "0.00001"
)


@dataclass
class TransferConfig:
    create_fee: DamlDecimal
    transfer_fee: SteppedRate
    lock_holder_fee: DamlDecimal

    def scale(self, factor):
        return TransferConfig(
            self.create_fee * factor,
            self.transfer_fee.scale(factor),
            self.lock_holder_fee * factor,
        )


# Wrapper around LF values to easily work with the protobuf encoding
@dataclass
class LfValue:
    value: dict

    # template OpenMiningRound -> round
    def get_open_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template OpenMiningRound -> amuletPrice
    def get_open_mining_round_amulet_price(self):
        return self.__get_record_field("amuletPrice").__get_numeric()

    # template OpenMiningRound -> config
    def get_open_mining_transfer_config(self):
        config = self.__get_record_field("transferConfigUsd")
        create_fee = config.__get_record_field("createFee").get_fixed_fee()
        transfer_fee = config.__get_record_field("transferFee").__get_stepped_rate()
        lock_holder_fee = config.__get_record_field("lockHolderFee").get_fixed_fee()
        return TransferConfig(
            create_fee,
            transfer_fee,
            lock_holder_fee,
        )

    # data FixedFee -> fee
    def get_fixed_fee(self):
        return self.__get_record_field("fee").__get_numeric()

    # template IssuingMiningRound -> round
    def get_issuing_mining_round_round(self):
        return self.__get_record_field("round").__get_round_number()

    # template SummarizingMiningRound -> round
    def get_summarizing_mining_round_round(self):
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

    # choice DsoRules_MiningRound_Close -> issuingRoundCid
    def get_mining_round_close_issuing_round_cid(self):
        return self.__get_record_field("issuingRoundCid").get_contract_id()

    # template Amulet -> owner
    def get_amulet_owner(self):
        return self.__get_record_field("owner").__get_party()

    # template Amulet -> amount
    def get_amulet_amount(self):
        return self.__get_record_field("amount")

    # template LockedAmulet -> amulet
    def get_locked_amulet_amulet(self):
        return self.__get_record_field("amulet")

    # template LockedAmulet -> amulet
    def get_locked_amulet_time_lock(self):
        return self.__get_record_field("lock")

    # choice DsoRules_Amulet_Expire -> cid
    def get_dso_rules_amulet_expire_cid(self):
        return self.__get_record_field("cid").get_contract_id()

    # choice DsoRules_LockedAmulet_ExpireAmulet -> cid
    def get_dso_rules_locked_amulet_expire_cid(self):
        return self.__get_record_field("cid").get_contract_id()

    # choice DsoRules_ExpireAnsEntry -> cid
    def get_dso_rules_expire_ans_entry_cid(self):
        return self.__get_record_field("ansEntryCid").get_contract_id()

    # data TimeLock -> holders
    def get_time_lock_holders(self):
        return [
            x.__get_party() for x in self.__get_record_field("holders").__get_list()
        ]

    # data TimeLock -> expiresAt
    def get_time_lock_expires_at(self):
        return self.__get_record_field("expiresAt").__get_timestamp()

    # data ExpiringAmount -> initialAmount
    def get_expiring_amount_initial_amount(self):
        return self.__get_record_field("initialAmount").__get_numeric()

    # data ExpiringAmount -> initialAmount
    def get_expiring_amount_created_at(self):
        return self.__get_record_field("createdAt").__get_round_number()

    # data ExpiringAmount -> ratePerRound; data RatePerRound -> rate
    def get_expiring_amount_rate_per_round(self):
        return (
            self.__get_record_field("ratePerRound")
            .__get_record_field("rate")
            .__get_numeric()
        )

    # data DsoRules_ReceiveSvRewardCouponResult -> svRewardCoupons
    def get_receive_sv_reward_coupons_result_sv_reward_coupons(self):
        return [
            x.get_contract_id()
            for x in self.__get_record_field("svRewardCoupons").__get_list()
        ]

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

    # choice DsoRules_ExecuteConfirmedAction -> action
    def get_execute_confirmed_action_action(self):
        return self.__get_record_field("action").__get_variant()

    # choice DsoRules_AdvanceOpenMiningRounds -> latestRoundCid
    def get_advance_open_mining_rounds_latest_round_cid(self):
        return self.__get_record_field("latestRoundCid").get_contract_id()

    # ARC_AmuletRules -> amuletRulesAction
    def get_arc_amulet_rules_amulet_rules_action(self):
        return self.__get_record_field("amuletRulesAction").__get_variant()

    # ARC_AnsEntryContext -> ansEntryContextCid
    def get_arc_ans_entry_context_ans_entry_context_cid(self):
        return self.__get_record_field("ansEntryContextCid").get_contract_id()

    # ARC_AnsEntryContext -> ansEntryContextAction
    def get_arc_ans_entry_context_ans_entry_context_action(self):
        return self.__get_record_field("ansEntryContextAction").__get_variant()

    # CRARC_MiningRound_StartIssuing -> AmuletRules_MiningRound_StartIssuingResult
    def get_start_issuing_mining_round_cid(self):
        return self.__get_record_field("miningRoundCid").get_contract_id()

    # data AmuletRules_MiningRound_StartIssuingResult -> issuingRoundCid
    def get_start_issuing_result_issuing_round_cid(self):
        return self.__get_record_field("issuingRoundCid").get_contract_id()

    # choice AmuletRules_Transfer -> transfer
    def get_amulet_rules_transfer_transfer(self):
        return self.__get_record_field("transfer")

    # data Transfer -> sender
    def get_transfer_sender(self):
        return self.__get_record_field("sender").__get_party()

    # data Transfer -> provider
    def get_transfer_provider(self):
        return self.__get_record_field("provider").__get_party()

    # data Transfer -> inputs
    def get_transfer_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # data Transfer -> outputs
    def get_transfer_outputs(self):
        return self.__get_record_field("outputs").__get_list()

    # data TransferResult -> round
    def get_transfer_result_round(self):
        return self.__get_record_field("round").__get_round_number()

    # data TransferResult -> summary
    def get_transfer_result_summary(self):
        return self.__get_record_field("summary")

    # data TransferResult -> summary; data TransferSummary -> senderChangeFee
    def get_transfer_result_sender_change_fee(self):
        return (
            self.get_transfer_result_summary().get_transfer_summary_sender_change_fee()
        )

    # data TransferSummary -> senderChangeFee
    def get_transfer_summary_sender_change_fee(self):
        return self.__get_record_field("senderChangeFee").__get_numeric()

    # data TransferResult -> summary; data TransferSummary -> senderChangeAmount
    def get_transfer_result_sender_change_amount(self):
        return (
            self.get_transfer_result_summary().get_transfer_summary_sender_change_amount()
        )

    # data TransferSummary -> senderChangeAmount
    def get_transfer_summary_sender_change_amount(self):
        return self.__get_record_field("senderChangeAmount").__get_numeric()

    # data TransferResult -> summary; data TransferSummary -> outputFees
    def get_transfer_result_output_fees(self):
        return [
            d.__get_numeric()
            for d in self.get_transfer_result_summary()
            .__get_record_field("outputFees")
            .__get_list()
        ]

    # data TransferResult -> senderChangeAmulet
    def get_transfer_result_sender_change_amulet(self):
        optional = self.__get_record_field("senderChangeAmulet").__get_optional()
        if optional:
            return optional.get_contract_id()
        else:
            return None

    # data TransferResult -> createdAmulets
    def get_transfer_result_created_amulets(self):
        return [
            x.__get_variant()
            for x in self.__get_record_field("createdAmulets").__get_list()
        ]

    # data ValidatorLicense_ReceiveFaucetCouponResult -> faucetCoupon
    def get_receive_validator_faucet_coupon_result_coupon_cid(self):
        return self.__get_record_field("couponCid").get_contract_id()

    # data ValidatorLicense_RecordValidatorLivenessActivityResult -> livenessActivityRecord
    def get_record_validator_liveness_activity_result_record_cid(self):
        return self.__get_record_field("couponCid").get_contract_id()

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

    # data AmuletRules_DevNet_TapResult -> amuletSum
    def get_tap_result_amulet_sum(self):
        return self.__get_record_field("amuletSum")

    # data AmuletRules_MintResult -> amuletSum
    def get_mint_result_amulet_sum(self):
        return self.__get_record_field("amuletSum")

    # data LockedAmulet_UnlockResult -> amuletSum
    def get_locked_amulet_unlock_result_amulet_sum(self):
        return self.__get_record_field("amuletSum")

    # data LockedAmulet_OwnerExpireLock -> amuletSum
    def get_locked_amulet_owner_expire_lock_result_amulet_sum(self):
        return self.__get_record_field("amuletSum")

    # data AmuletCreateSummary -> amulet
    def get_amulet_summary_amulet(self):
        return self.__get_record_field("amulet").get_contract_id()

    # data AmuletCreateSummary -> round
    def get_amulet_summary_round(self):
        return self.__get_record_field("round").__get_round_number()

    # choice AmuletRules_BuyMemberTraffic -> inputs
    def get_buy_member_traffic_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # choice AmuletRules_BuyMemberTraffic -> provider
    def get_buy_member_traffic_provider(self):
        return self.__get_record_field("provider").__get_party()

    # choice AmuletRules_BuyMemberTraffic -> memberId
    def get_buy_member_traffic_member_id(self):
        return self.__get_record_field("memberId").__get_text()

    # choice AmuletRules_BuyMemberTraffic -> synchronizerId
    def get_buy_member_traffic_synchronizer_id(self):
        return self.__get_record_field("synchronizerId").__get_text()

    # choice AmuletRules_BuyMemberTraffic -> migrationId
    def get_buy_member_traffic_migration_id(self):
        return self.__get_record_field("migrationId").__get_int64()

    # choice AmuletRules_BuyMemberTraffic -> trafficAmount
    def get_buy_member_traffic_traffic_amount(self):
        return self.__get_record_field("trafficAmount").__get_int64()

    # data AmuletRules_BuyMemberTrafficResult -> round
    def get_buy_member_traffic_result_round(self):
        return self.__get_record_field("round").__get_round_number()

    # data AmuletRules_BuyMemberTrafficResult -> senderChangeAmulet
    def get_buy_member_traffic_result_sender_change_amulet(self):
        optional = self.__get_record_field("senderChangeAmulet").__get_optional()
        if optional:
            return optional.get_contract_id()
        else:
            return None

    # data AmuletRules_BuyMemberTrafficResult -> amuletPaid
    def get_buy_member_traffic_result_amulet_paid(self):
        return self.__get_record_field("amuletPaid").__get_numeric()

    # data AmuletRules_BuyMemberTrafficResult -> amuletPaid
    def get_buy_member_traffic_result_transfer_summary(self):
        return self.__get_record_field("summary")

    # choice AmuletRules_CreateExternalPartySetupProposal -> inputs
    def get_create_external_party_setup_proposal_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # choice AmuletRules_CreateExternalPartySetupProposalResult -> user
    def get_create_external_party_setup_proposal_user(self):
        return self.__get_record_field("user").__get_party()

    # choice AmuletRules_CreateExternalPartySetupProposalResult -> validator
    def get_create_external_party_setup_proposal_validator(self):
        return self.__get_record_field("validator").__get_party()

    # data AmuletRules_CreateExternalPartySetupProposalResult -> transferResult
    def get_create_external_party_setup_proposal_result_transfer_result(self):
        return self.__get_record_field("transferResult")

    # data AmuletRules_CreateExternalPartySetupProposalResult -> amuletPaid
    def get_create_external_party_setup_proposal_result_amulet_paid(self):
        return self.__get_record_field("amuletPaid").__get_numeric()

    # choice AmuletRules_CreateTransferPreapproval -> inputs
    def get_create_transfer_preapproval_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # choice AmuletRules_CreateTransferPreapproval -> receiver
    def get_create_transfer_preapproval_receiver(self):
        return self.__get_record_field("receiver").__get_party()

    # choice AmuletRules_CreateTransferPreapproval -> provider
    def get_create_transfer_preapproval_provider(self):
        return self.__get_record_field("provider").__get_party()

    # data AmuletRules_CreateTransferPreapprovalResult -> transferResult
    def get_create_transfer_preapproval_result_transfer_result(self):
        return self.__get_record_field("transferResult")

    # data AmuletRules_CreateTransferPreapprovalResult -> amuletPaid
    def get_create_transfer_preapproval_result_amulet_paid(self):
        return self.__get_record_field("amuletPaid").__get_numeric()

    # choice TransferPreapproval_Renew -> inputs
    def get_renew_transfer_preapproval_inputs(self):
        return [
            x.__get_variant() for x in self.__get_record_field("inputs").__get_list()
        ]

    # data TransferPreapproval_RenewResult -> receiver
    def get_renew_transfer_preapproval_receiver(self):
        return self.__get_record_field("receiver").__get_party()

    # data TransferPreapproval_RenewResult -> provider
    def get_renew_transfer_preapproval_provider(self):
        return self.__get_record_field("provider").__get_party()

    # data TransferPreapproval_RenewResult -> transferResult
    def get_renew_transfer_preapproval_result_transfer_result(self):
        return self.__get_record_field("transferResult")

    # data TransferPreapproval_RenewResult -> amuletPaid
    def get_renew_transfer_preapproval_result_amulet_paid(self):
        return self.__get_record_field("amuletPaid").__get_numeric()

    # data TransferOutput -> receiver
    def get_transfer_output_receiver(self):
        return self.__get_record_field("receiver").__get_party()

    # data TransferOutput -> amount
    def get_transfer_output_amount(self):
        return self.__get_record_field("amount").__get_numeric()

    # data TransferOutput -> receiverFeeRatio
    def get_transfer_output_receiver_fee_ratio(self):
        return self.__get_record_field("receiverFeeRatio").__get_numeric()

    # template AppRewardCoupon -> provider
    def get_app_reward_provider(self):
        return self.__get_record_field("provider").__get_party()

    # template AppRewardCoupon -> featured
    def get_app_reward_featured(self):
        return self.__get_record_field("featured").__get_bool()

    # template AppRewardCoupon -> amount
    def get_app_reward_amount(self):
        return self.__get_record_field("amount").__get_numeric()

    # template UnclaimedReward -> amount
    def get_unclaimed_reward_amount(self):
        return self.__get_record_field("amount").__get_numeric()

    # template AppRewardCoupon -> round
    def get_app_reward_round(self):
        return self.__get_record_field("round").__get_round_number()

    # ExtTransferInput -> optInputValidatorFaucetCoupon
    def get_ext_transfer_input_validator_faucet_coupon(self):
        cid = self.__get_record_field("optInputValidatorFaucetCoupon")
        if cid:
            return cid.get_contract_id()
        else:
            return None

    # template AnsEntryContext -> name
    def get_ans_entry_context_name(self):
        return self.__get_record_field("name").__get_text()

    # template AnsEntryContext -> user
    def get_ans_entry_context_user(self):
        return self.__get_record_field("user").__get_party()

    # template AnsEntryContext -> reference
    def get_ans_entry_context_subscription_request(self):
        return self.__get_record_field("reference").get_contract_id()

    # template AnsEntry -> user
    def get_ans_entry_user(self):
        return self.__get_record_field("user").__get_party()

    # template AnsEntry -> name
    def get_ans_entry_name(self):
        return self.__get_record_field("name").__get_text()

    # template SubscriptionIdleState -> reference
    def get_subscription_idle_state_reference(self):
        return self.__get_record_field("reference").get_contract_id()

    # choice DsoRules_TerminateSubscription -> ansEntryContextCid
    def get_dso_rules_terminate_subscription_ans_entry_context_cid(self):
        return self.__get_record_field("ansEntryContextCid").get_contract_id()

    # choice DsoRules_TerminateSubscription -> ansEntryContextCid
    def get_dso_rules_expire_subscription_ans_entry_context_cid(self):
        return self.__get_record_field("ansEntryContextCid").get_contract_id()

    def get_dso_rules_expire_subscription_subscription_idle_state_cid(self):
        return self.__get_record_field("subscriptionIdleStateCid").get_contract_id()

    # data DsoRules_Amulet_ExpireResult -> expireSum
    def get_dso_rules_amulet_expire_result_expire_sum(self):
        return self.__get_record_field("expireSum")

    # data DsoRules_LockedAmulet_ExpireResult -> expireSum
    def get_dso_rules_locked_amulet_expire_result_expire_sum(self):
        return self.__get_record_field("expireSum")

    # data AmuletExpireSummary -> round
    def get_amulet_expire_summary_round(self):
        return self.__get_record_field("round").__get_round_number()

    # data AmuletRules_ConvertFeaturedAppActivityMarkers -> openRoundCid
    def get_amuletrules_convertfeaturedappactivitymarkers_openroundcid(self):
        return self.__get_record_field("openMiningRoundCid").get_contract_id()

    # data TransferInstructionResult -> output
    def get_transferinstructionresult_output(self):
        return self.__get_record_field("output").__get_variant()

    # data SteppedRate
    def __get_stepped_rate(self):
        initial_rate = self.__get_record_field("initialRate").__get_numeric()
        steps = [
            x.__get_stepped_rate_step()
            for x in self.__get_record_field("steps").__get_list()
        ]
        return SteppedRate(initial_rate, steps)

    # (Decimal, Decimal)
    def __get_stepped_rate_step(self):
        return (
            self.__get_record_field("_1").__get_numeric(),
            self.__get_record_field("_2").__get_numeric(),
        )

    def __get_optional(self):
        if self.value:
            return self
        else:
            return None

    def __get_numeric(self):
        try:
            return DamlDecimal(self.value)
        except Exception as e:
            raise LfValueParseException(self, "numeric", e)

    def __get_text(self):
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

    def __get_party(self):
        if isinstance(self.value, str):
            return self.value
        else:
            raise LfValueParseException(
                self, "party", f"Expected string, got {type(self.value)}"
            )

    def __get_int64(self):
        try:
            return int(self.value)
        except Exception as e:
            raise LfValueParseException(self, "int64", e)

    def get_contract_id(self):
        if isinstance(self.value, str):
            return self.value
        else:
            raise LfValueParseException(
                self, "contract id", f"Expected string, got {type(self.value)}"
            )

    def __get_list(self):
        if isinstance(self.value, list):
            return [LfValue(x) for x in self.value]
        else:
            raise LfValueParseException(
                self, "list", f"Expected list, got {type(self.value)}"
            )

    def __get_record_field(self, field_name):
        try:
            if field_name in self.value:
                return LfValue(self.value[field_name])
            else:
                raise LfValueParseException(
                    self, "record", f"Missing record field {field_name}"
                )
        except Exception as e:
            raise LfValueParseException(self, "record", e)

    def __get_round_number(self):
        return self.__get_record_field("number").__get_int64()

    def __get_bool(self):
        if isinstance(self.value, bool):
            return self.value
        else:
            raise LfValueParseException(
                self, "boolean", f"Expected boolean, got {type(self.value)}"
            )

    def __get_timestamp(self):
        try:
            return datetime.fromisoformat(self.value)
        except Exception as e:
            raise LfValueParseException(self, "timestamp", e)


class LfValueParseException(Exception):
    def __init__(self, value, type, details):
        message = f"Could not parse value {value.value} as {type}: {details}"
        self.value = value.value
        self.type = type
        self.details = details
        self.message = message
        super().__init__(message)


@dataclass
class ExercisedEvent:
    event_id: str
    template_id: TemplateId
    choice_name: str
    contract_id: str
    exercise_argument: LfValue
    exercise_result: LfValue
    child_event_ids: list
    is_consuming: bool


@dataclass
class CreatedEvent:
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
            "event_id": self.event_id,
            "template_id": self.template_id.template_id,
            "contract_id": self.contract_id,
            "create_arguments": self.payload.value,
        }


class Event:
    @staticmethod
    def parse(json, event_id):
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
            )


@dataclass
class TransactionTree:
    root_event_ids: list
    events_by_id: dict
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
        self.by_contract_id = {
            event.contract_id: event
            for event in self.events_by_id.values()
            if isinstance(event, CreatedEvent)
        }
        self.synchronizer_id = synchronizer_id

    @staticmethod
    def parse(json):
        try:
            return TransactionTree(
                json["root_event_ids"],
                {
                    event_id: Event.parse(event, event_id)
                    for event_id, event in json["events_by_id"].items()
                },
                json["migration_id"],
                datetime.fromisoformat(json["record_time"]),
                json["update_id"],
                json["workflow_id"],
                json["synchronizer_id"],
            )
        except Exception as e:
            raise Exception(f"Failed to parse transaction tree: {json}") from e

    def acs_diff(self):
        created_events = {}
        archived_events = {}
        # Not iterating in execution order but doesn't matter.
        for event in self.events_by_id.values():
            if event.template_id.qualified_name in TemplateQualifiedNames.all_tracked:
                if isinstance(event, CreatedEvent):
                    created_events[event.contract_id] = event
                elif isinstance(event, ExercisedEvent):
                    if event.is_consuming:
                        archived_events[event.contract_id] = event
                else:
                    raise Exception(
                        f"Encountered Unknown event when producing acs diff: {event}"
                    )
        non_transient_created_events = {
            cid: ev
            for cid, ev in created_events.items()
            if cid not in archived_events.keys()
        }
        non_transient_archived_events = {
            cid: ev
            for cid, ev in archived_events.items()
            if cid not in created_events.keys()
        }
        return AcsDiff(non_transient_created_events, non_transient_archived_events)


def get_transaction_id(transaction):
    hash_length = 12
    return f"{transaction.update_id[0:hash_length]}..."


class AcsDiff:
    def __init__(self, created_events, archived_events):
        self.created_events = created_events
        self.archived_events = archived_events


@dataclass
class TransferSummary:
    effective_inputs: list
    all_outputs: list
    fees: list

    def is_valid(self):
        return sum(self.effective_inputs) == sum(
            [o.initial_amount for o in self.all_outputs]
        ) + sum(self.fees)

    def get_locked_total(self):
        return sum([o.initial_amount for o in self.all_outputs if o.is_locked()])

    def get_unlocked_total(self):
        return sum([o.initial_amount for o in self.all_outputs if not o.is_locked()])

    def get_fees_total(self):
        return sum(self.fees)

    def get_error(self, event_type):
        if not self.is_valid():
            return f"Inputs and outputs of {event_type} do not match up, expected: {sum(self.effective_inputs)} == {sum([o.initial_amount for o in self.all_outputs])} + {sum(self.fees)}"

    def get_all_owners(self):
        return [o.owner for o in self.all_outputs]

    def get_all_lock_holders(self):
        return sum([o.lock_holders for o in self.all_outputs], [])


@dataclass
class TransferInput:
    source: str
    initial_amount: Optional[DamlDecimal]
    effective_amount: DamlDecimal
    round: int


class TransferInputs:
    def __init__(
        self,
        app_rewards,
        validator_rewards,
        validator_faucets,
        validator_liveness_activity_records,
        sv_rewards,
        amulets,
        round_number,
        issuing_mining_rounds,
    ):
        self.app_rewards = app_rewards
        self.validator_rewards = validator_rewards
        self.validator_faucets = validator_faucets
        self.validator_liveness_activity_records = validator_liveness_activity_records
        self.sv_rewards = sv_rewards
        self.amulets = amulets
        self.round_number = round_number
        self.issuing_mining_rounds_by_round = {
            r.payload.get_issuing_mining_round_round(): r.payload
            for r in issuing_mining_rounds.values()
        }

    def summarize_reward_type(self, header, rewards_by_round, get_issuance, get_amount):
        output = []
        effective_inputs = []
        all_inputs = []
        total_cc = DamlDecimal(0)
        if rewards_by_round:
            output += [f"{header}:"]
            for round_number, rewards in rewards_by_round.items():
                issuing = get_issuance(
                    self.issuing_mining_rounds_by_round[round_number]
                )
                round_total_cc = DamlDecimal(0)
                total_amount = DamlDecimal(0)
                for reward in rewards:
                    amount = get_amount(reward.payload)
                    total_amount += amount
                    round_total_cc += amount * issuing

                all_inputs += [
                    TransferInput(header, None, round_total_cc, round_number)
                ]

                effective_inputs += [round_total_cc]
                total_cc += round_total_cc
                # Note: round_total_cc is not necessarily exactly total_amount * issuing due to
                # (a + b) * z not being equal to a * z + b * z in DamlDecimal.
                # Displaying individual rewards is way too noisy though so we accept this.
                output += [
                    f"  recorded in round {round_number}: -{round_total_cc} = -{total_amount} @ {issuing}"
                ]

        return (output, effective_inputs, total_cc, all_inputs)

    def summary(self, exercised_event):
        subtract_holding_fees_per_round = KnownPackageIds.deducts_holding_fees(
            exercised_event.template_id.package_id
        )
        output = []
        effective_inputs = []
        unfeatured_app_rewards = {}
        featured_app_rewards = {}
        for round_number, rewards in self.app_rewards.items():
            for reward in rewards:
                if reward.payload.get_app_reward_featured():
                    featured_app_rewards.setdefault(round_number, []).append(reward)
                else:
                    unfeatured_app_rewards.setdefault(round_number, []).append(reward)

        (unfeatured_outputs, unfeatured_inputs, unfeatured_total_cc, all_unfeatured) = (
            self.summarize_reward_type(
                "unfeatured_app_activity_record",
                unfeatured_app_rewards,
                lambda r: r.get_issuing_mining_round_issuance_per_unfeatured_app_reward(),
                lambda r: r.get_app_reward_amount(),
            )
        )
        output += unfeatured_outputs
        effective_inputs += unfeatured_inputs

        (featured_outputs, featured_inputs, featured_total_cc, all_featured) = (
            self.summarize_reward_type(
                "featured_app_activity_record",
                featured_app_rewards,
                lambda r: r.get_issuing_mining_round_issuance_per_featured_app_reward(),
                lambda r: r.get_app_reward_amount(),
            )
        )
        output += featured_outputs
        effective_inputs += featured_inputs

        (validator_outputs, validator_inputs, validator_total_cc, all_validator) = (
            self.summarize_reward_type(
                "validator_activity_record",
                self.validator_rewards,
                lambda r: r.get_issuing_mining_round_issuance_per_validator_reward(),
                lambda r: r.get_validator_reward_amount(),
            )
        )
        output += validator_outputs
        effective_inputs += validator_inputs

        (
            validator_faucet_outputs,
            validator_faucet_inputs,
            validator_faucet_total_cc,
            all_validator_faucet,
        ) = self.summarize_reward_type(
            "validator_faucet_activity_record",
            self.validator_faucets,
            lambda r: r.get_issuing_mining_round_issuance_per_validator_faucet(),
            lambda r: DamlDecimal(1),  # faucets always have value 1
        )
        output += validator_faucet_outputs
        effective_inputs += validator_faucet_inputs

        (
            validator_liveness_activity_record_outputs,
            validator_liveness_activity_record_inputs,
            validator_liveness_activity_record_total_cc,
            all_validator_liveness_activity_record,
        ) = self.summarize_reward_type(
            "validator_faucet_activity_record",
            self.validator_liveness_activity_records,
            lambda r: r.get_issuing_mining_round_issuance_per_validator_faucet(),
            lambda r: DamlDecimal(1),  # records always have value 1
        )
        output += validator_liveness_activity_record_outputs
        effective_inputs += validator_liveness_activity_record_inputs

        (sv_outputs, sv_inputs, sv_total_cc, all_sv_activity) = (
            self.summarize_reward_type(
                "sv_activity_record",
                self.sv_rewards,
                lambda r: r.get_issuing_mining_round_issuance_per_sv_reward(),
                lambda r: r.get_sv_reward_coupon_weight(),
            )
        )
        output += sv_outputs
        effective_inputs += sv_inputs

        reward_total_cc = (
            unfeatured_total_cc
            + featured_total_cc
            + validator_total_cc
            + validator_faucet_total_cc
            + validator_liveness_activity_record_total_cc
            + sv_total_cc
        )

        initial_amulet_total_cc = DamlDecimal(0)
        amulet_total_cc = DamlDecimal(0)
        all_amulet = []
        if self.amulets:
            output += ["amulets:"]
            for amulet in self.amulets:
                amount = amulet.payload.get_amulet_amount()
                effective = EffectiveAmount.from_amount_and_round(
                    amount, self.round_number, subtract_holding_fees_per_round
                )
                effective_inputs += [effective.effective_amount]

                initial_amulet_total_cc += effective.initial_amount
                amulet_total_cc += effective.effective_amount
                all_amulet += [
                    TransferInput(
                        "amulet",
                        effective.initial_amount,
                        effective.effective_amount,
                        effective.created_at,
                    )
                ]
                output += [
                    f"  created in round {effective.created_at}: -{effective.effective_amount} = -({effective.initial_amount} - {effective.rate_per_round} * {effective.round_diff})"
                ]

        all_inputs = (
            all_unfeatured
            + all_featured
            + all_validator
            + all_validator_faucet
            + all_validator_liveness_activity_record
            + all_sv_activity
            + all_amulet
        )

        return (
            "\n".join(output),
            effective_inputs,
            reward_total_cc,
            initial_amulet_total_cc,
            amulet_total_cc,
            all_inputs,
        )


@dataclass
class EffectiveAmount:
    effective_amount: DamlDecimal
    initial_amount: DamlDecimal
    created_at: int
    rate_per_round: DamlDecimal
    round_diff: int

    def from_amount_and_round(amount, round_number, subtract_holding_fees_per_round):
        created_at = amount.get_expiring_amount_created_at()
        initial_amount = amount.get_expiring_amount_initial_amount()
        rate_per_round = amount.get_expiring_amount_rate_per_round()
        round_diff = max(0, round_number - created_at)
        # kept for backwards-compatibility (important for compatibility tests)
        if subtract_holding_fees_per_round:
            effective_amount = max(
                initial_amount - DamlDecimal(round_diff) * rate_per_round,
                DamlDecimal("0"),
            )
        else:
            effective_amount = initial_amount
        return EffectiveAmount(
            effective_amount, initial_amount, created_at, rate_per_round, round_diff
        )


@dataclass
class PerPartyState:
    amulets: list
    locked_amulets: list
    sv_reward_coupons: dict
    unfeatured_app_reward_coupons: dict
    featured_app_reward_coupons: dict
    validator_reward_coupons: list
    validator_faucet_coupons: list
    validator_activity_record: list

    open_mining_round_numbers: list

    def __init__(self, args, open_mining_round_numbers):
        self.args = args
        self.amulets = []
        self.locked_amulets = []
        self.sv_reward_coupons = {}
        self.unfeatured_app_reward_coupons = {}
        self.featured_app_reward_coupons = {}
        self.validator_reward_coupons = {}
        self.validator_faucet_coupons = {}
        self.validator_activity_records = {}
        self.open_mining_round_numbers = open_mining_round_numbers

    # def __effective_amount__(self, amount, round_number):
    #     effective = EffectiveAmount.from_amount_and_round(amount, round_number)
    #     return f"round {round_number}: {effective.effective_amount} = {effective.initial_amount} - {effective.round_diff} * {effective.rate_per_round}"

    def __str__(self):
        lines = []
        if self.amulets:
            lines += ["amulets:"]
            for amulet in self.amulets:
                amount = amulet.payload.get_amulet_amount()
                created_at = amount.get_expiring_amount_created_at()
                initial_amount = amount.get_expiring_amount_initial_amount()
                rate_per_round = amount.get_expiring_amount_rate_per_round()
                lines += [
                    f"  {initial_amount}, created in round {created_at}, holding fee rate: {rate_per_round} CC/round"
                ]
                # TODO(#2248): change this to show TTL of amulet
                # if not self.args.hide_details:
                #     lines += [
                #         "    projected effective amounts: "
                #         + ", ".join(
                #             [
                #                 self.__effective_amount__(amount, round_number)
                #                 for round_number in self.open_mining_round_numbers
                #             ]
                #         ),
                #     ]
        if self.locked_amulets:
            lines += ["locked_amulets:"]
            for locked_amulet in self.locked_amulets:
                amulet = locked_amulet.payload.get_locked_amulet_amulet()
                amount = amulet.get_amulet_amount()
                created_at = amount.get_expiring_amount_created_at()
                initial_amount = amount.get_expiring_amount_initial_amount()
                rate_per_round = amount.get_expiring_amount_rate_per_round()
                lock = locked_amulet.payload.get_locked_amulet_time_lock()
                lock_holders = lock.get_time_lock_holders()
                expires_at = lock.get_time_lock_expires_at()
                lines += [
                    f"  {initial_amount}, created in round {created_at}, holding fee rate: {rate_per_round} CC/round, locked to owner and {lock_holders} until {expires_at}",
                ]
                # TODO(#2248): change this to show TTL of amulet
                # if not self.args.hide_details:
                #     lines += [
                #         "    projected effective amounts: "
                #         + ", ".join(
                #             [
                #                 self.__effective_amount__(amount, round_number)
                #                 for round_number in self.open_mining_round_numbers
                #             ]
                #         ),
                #     ]
        if self.sv_reward_coupons:
            lines += [f"sv_activity_records: {self.sv_reward_coupons}"]
        if self.unfeatured_app_reward_coupons:
            lines += [
                f"unfeatured_app_activity_records: {self.unfeatured_app_reward_coupons}"
            ]
        if self.featured_app_reward_coupons:
            lines += [
                f"featured_app_activity_records: {self.featured_app_reward_coupons}"
            ]
        if self.validator_reward_coupons:
            lines += [f"validator_activity_records: {self.validator_reward_coupons}"]
        if self.validator_faucet_coupons or self.validator_activity_records:
            lines += [
                f"validator_liveness_activity_records: {self.validator_faucet_coupons | self.validator_activity_records} "
            ]
        return "\n".join(lines)


@dataclass
class TransferOutput:
    initial_amount: DamlDecimal
    owner: str
    lock_holders: list

    def is_locked(self):
        return len(self.lock_holders) > 0


# The state at a given record time
class State:
    args: dict
    active_contracts: dict[str, CreatedEvent]
    record_time: datetime
    synchronizer_id: Optional[str]
    csv_report: CSVReport
    for_round: Optional[int]
    total_minted: DamlDecimal
    total_burnt: DamlDecimal

    def __init__(
        self,
        args,
        active_contracts,
        record_time,
        synchronizer_id,
        csv_report,
        total_minted,
        total_burnt,
    ):
        self.args = args
        self.active_contracts = active_contracts
        self.record_time = record_time
        self.synchronizer_id = synchronizer_id
        self.csv_report = csv_report
        self.for_round = None
        self.total_minted = total_minted
        self.total_burnt = total_burnt

    def clone(self):
        return State(
            self.args,
            self.active_contracts.copy(),
            self.record_time,
            self.synchronizer_id,
            self.csv_report,
            self.total_minted,
            self.total_burnt,
        )

    def clone_for_round(self, for_round):
        s = self.clone()
        s.for_round = for_round
        return s

    @classmethod
    def from_json(cls, args, json, csv_report):
        return cls(
            args,
            {
                cid: CreatedEvent.from_json(contract)
                for cid, contract in json["active_contracts"].items()
            },
            datetime.fromisoformat(json["record_time"]),
            json["synchronizer_id"],
            csv_report,
            DamlDecimal(json["total_minted"]),
            DamlDecimal(json["total_burnt"]),
        )

    def to_json(self):
        return {
            "active_contracts": {
                cid: contract.to_json()
                for cid, contract in self.active_contracts.items()
            },
            "record_time": self.record_time.isoformat(),
            "synchronizer_id": self.synchronizer_id,
            "total_minted": str(self.total_minted),
            "total_burnt": str(self.total_burnt),
        }

    def _txinfo(self, transaction, operation, message, parties=None):
        if self.for_round and not self.args.verbose:
            return

        if parties and len([p for p in parties if _party_enabled(self.args, p)]) == 0:
            return False

        msg = message.split("\n", 1)

        round_flag = f":r{self.for_round}" if self.for_round else ""
        full_log_message = f"{transaction.record_time} {get_transaction_id(transaction)} ({operation}{round_flag}) - {msg[0]}"
        if len(msg) > 1 and not self.args.hide_details:
            full_log_message += "\n" + textwrap.indent(msg[1], "    ")
        LOG.info(full_log_message)

        return True

    def _report_line(self, transaction, line_type, line_info, parties=None):
        if self.for_round:
            return

        written = self._txinfo(
            transaction, f"ReportLine{line_type}", str(line_info), parties=parties
        )

        if written:
            self.csv_report.report_line(line_type, line_info)

    def flush_report(self):
        self.csv_report.flush()

    def _fail(self, transaction, message, cause=None, recoverable=True):
        if not self.args.stop_on_error and recoverable:
            message = f"{message} (will attempt to recover from acs_diff and restart)"

        LOG.error(message)

        if not recoverable:
            raise Exception(
                f"Unrecoveable error, stopping export (error: {message})"
            ) from cause

        if self.args.stop_on_error:
            raise Exception(
                f"--stop-on-error set, stopping export (error: {message})"
            ) from cause

    def summary(self):
        amulets = self.list_contracts(TemplateQualifiedNames.amulet)
        locked_amulets = self.list_contracts(TemplateQualifiedNames.locked_amulet)
        app_rewards = self.list_contracts(TemplateQualifiedNames.app_reward_coupon)
        validator_rewards = self.list_contracts(
            TemplateQualifiedNames.validator_reward_coupon
        )
        validator_faucets = self.list_contracts(
            TemplateQualifiedNames.validator_faucet_coupon
        )
        validator_activity_records = self.list_contracts(
            TemplateQualifiedNames.validator_liveness_activity_record
        )
        sv_rewards = self.list_contracts(TemplateQualifiedNames.sv_reward_coupon)
        open_mining_rounds = self.list_contracts(
            TemplateQualifiedNames.open_mining_round
        )
        summarizing_mining_rounds = self.list_contracts(
            TemplateQualifiedNames.summarizing_mining_round
        )
        issuing_mining_rounds = self.list_contracts(
            TemplateQualifiedNames.issuing_mining_round
        )
        [r.payload.get_open_mining_round_round() for r in open_mining_rounds.values()]
        open_mining_round_numbers = sorted(
            [
                r.payload.get_open_mining_round_round()
                for r in open_mining_rounds.values()
            ]
        )
        highest_open_mining_round = max(open_mining_round_numbers)
        summarizing_mining_round_numbers = sorted(
            [
                r.payload.get_summarizing_mining_round_round()
                for r in summarizing_mining_rounds.values()
            ]
        )
        issuing_mining_round_numbers = sorted(
            [
                r.payload.get_issuing_mining_round_round()
                for r in issuing_mining_rounds.values()
            ]
        )
        per_party_states = {}
        for amulet in amulets.values():
            owner = amulet.payload.get_amulet_owner()
            per_party_states.setdefault(
                owner, PerPartyState(self.args, open_mining_round_numbers)
            )
            per_party_states[owner].amulets += [amulet]
        for locked_amulet in locked_amulets.values():
            amulet = locked_amulet.payload.get_locked_amulet_amulet()
            owner = amulet.get_amulet_owner()
            per_party_states.setdefault(
                owner, PerPartyState(self.args, open_mining_round_numbers)
            )
            per_party_states[owner].locked_amulets += [locked_amulet]
        for app_reward in app_rewards.values():
            provider = app_reward.payload.get_app_reward_provider()
            per_party_states.setdefault(
                provider, PerPartyState(self.args, open_mining_round_numbers)
            )
            round_number = app_reward.payload.get_app_reward_round()
            amount = app_reward.payload.get_app_reward_amount()
            if app_reward.payload.get_app_reward_featured():
                per_party_states[provider].featured_app_reward_coupons.setdefault(
                    round_number, DamlDecimal("0")
                )
                per_party_states[provider].featured_app_reward_coupons[
                    round_number
                ] += amount
            else:
                per_party_states[provider].unfeatured_app_reward_coupons.setdefault(
                    round_number, DamlDecimal("0")
                )
                per_party_states[provider].unfeatured_app_reward_coupons[
                    round_number
                ] += amount
        for validator_reward in validator_rewards.values():
            user = validator_reward.payload.get_validator_reward_user()
            per_party_states.setdefault(
                user, PerPartyState(self.args, open_mining_round_numbers)
            )
            round_number = validator_reward.payload.get_validator_reward_round()
            amount = validator_reward.payload.get_validator_reward_amount()
            per_party_states[user].validator_reward_coupons.setdefault(
                round_number, DamlDecimal("0")
            )
            per_party_states[user].validator_reward_coupons[round_number] += amount
        for validator_faucet in validator_faucets.values():
            validator = validator_faucet.payload.get_validator_faucet_validator()
            per_party_states.setdefault(
                validator, PerPartyState(self.args, open_mining_round_numbers)
            )
            round_number = validator_faucet.payload.get_validator_faucet_round()
            per_party_states[validator].validator_faucet_coupons.setdefault(
                round_number, 0
            )
            per_party_states[validator].validator_faucet_coupons[round_number] += 1
        for validator_activity_record in validator_activity_records.values():
            validator = (
                validator_activity_record.payload.get_validator_liveness_activity_record_validator()
            )
            per_party_states.setdefault(
                validator, PerPartyState(self.args, open_mining_round_numbers)
            )
            round_number = (
                validator_activity_record.payload.get_validator_liveness_activity_record_round()
            )
            per_party_states[validator].validator_activity_records.setdefault(
                round_number, 0
            )
            per_party_states[validator].validator_activity_records[round_number] += 1
        for sv_reward in sv_rewards.values():
            beneficiary = sv_reward.payload.get_sv_reward_coupon_beneficiary()
            per_party_states.setdefault(
                beneficiary, PerPartyState(self.args, open_mining_round_numbers)
            )
            round_number = sv_reward.payload.get_sv_reward_coupon_round()
            per_party_states[beneficiary].sv_reward_coupons.setdefault(
                round_number, DamlDecimal("0")
            )
            per_party_states[beneficiary].sv_reward_coupons[
                round_number
            ] += DamlDecimal(sv_reward.payload.get_sv_reward_coupon_weight())
        return "\n".join(
            [
                f"Summary at {self.record_time}"
                f" (Mining rounds, open: {open_mining_round_numbers}, summarizing: {summarizing_mining_round_numbers}, issuing: {issuing_mining_round_numbers})",
            ]
            + [
                f"    {party}:\n{textwrap.indent(str(state), '      ')}"
                for party, state in sorted(per_party_states.items())
                if _party_enabled(self.args, party)
            ]
        )

    def total_summary(self):
        unminted_coins = self.list_contracts(TemplateQualifiedNames.unclaimed_reward)
        unminted_total = DamlDecimal(0)
        for unminted_coin in unminted_coins.values():
            unminted_total += unminted_coin.payload.get_unclaimed_reward_amount()
        return "\n".join(
            [
                f"    Total Unminted: {unminted_total}"
                f"    Total Minted: {self.total_minted}"
                f"    Total Burnt: {self.total_burnt}"
            ]
        )

    def list_contracts(self, template_id):
        return {
            cid: contract
            for cid, contract in self.active_contracts.items()
            if contract.template_id.qualified_name == template_id
        }

    def active_issuing_mining_rounds(self):
        return self.list_contracts(TemplateQualifiedNames.issuing_mining_round)

    def get_ans_entry_context_by_subscription_request(self, subscription_request_cid):
        ans_entry_contexts = self.list_contracts(
            TemplateQualifiedNames.ans_entry_context
        )
        # Note: We currently don't properly handle the case where there is a SubscriptionRequest without
        # a corresponding AnsEntryContext. This should never happen for SubscriptionRequests visible to the DSO.
        return next(
            ans_entry_context
            for ans_entry_context in ans_entry_contexts.values()
            if ans_entry_context.payload.get_ans_entry_context_subscription_request()
            == subscription_request_cid
        )

    def get_open_mining_round_by_round_number(self, round_number):
        open_rounds = self.list_contracts(TemplateQualifiedNames.open_mining_round)
        return next(
            open_round
            for open_round in open_rounds.values()
            if open_round.payload.get_open_mining_round_round() == round_number
        )

    def _check_synchronizer_id(self, transaction):
        sid = transaction.synchronizer_id

        if self.synchronizer_id is None:
            self.synchronizer_id = sid
            return

        if self.synchronizer_id != sid:
            self._fail(
                transaction,
                f"Synchronizer ID mismatch between cache file and environment: "
                f"({self.synchronizer_id}!={sid}). Please reset the local cache "
                f"and retry.",
                recoverable=False,
            )

    def handle_transaction(self, transaction):
        self._check_synchronizer_id(transaction)

        previous_state = self.clone()
        result = HandleTransactionResult.empty()
        try:
            for event_id in transaction.root_event_ids:
                event = transaction.events_by_id[event_id]
                event_result = self.handle_root_event(transaction, event)
                result = result.merge(event_result)
        except Exception as e:
            self._fail(
                transaction,
                f"Encountered exception while processing transaction: {e}",
                cause=e,
            )

        for cid, ev in self.active_contracts.items():
            if not isinstance(ev, CreatedEvent):
                self._fail(
                    transaction,
                    f"Unexpected non-create event in active contracts for {cid}: {ev}",
                )

        # This is a sanity check to make sure the code does not
        # forget tracking an ACS change.
        acs_diff = transaction.acs_diff()

        created = self.active_contracts.keys() - previous_state.active_contracts.keys()
        archived = previous_state.active_contracts.keys() - self.active_contracts.keys()
        if created != acs_diff.created_events.keys():
            self._fail(
                transaction,
                f"Transaction created contracts {acs_diff.created_events}\nbut our state created {created}",
            )
            self.active_contracts = {
                k: v
                for k, v in (
                    previous_state.active_contracts | acs_diff.created_events
                ).items()
                if k not in acs_diff.archived_events.keys()
            }
        if archived != acs_diff.archived_events.keys():
            self._fail(
                transaction,
                f"Transaction archived contracts {acs_diff.archived_events}\nbut our state archived {archived}",
            )
            self.active_contracts = {
                k: v
                for k, v in (
                    previous_state.active_contracts | acs_diff.created_events
                ).items()
                if k not in acs_diff.archived_events.keys()
            }
        self.record_time = transaction.record_time

        if not self.args.hide_round_events:
            if result.new_closed_round:
                self._txinfo(transaction, "Closed Round", self.summary())
            elif result.new_open_rounds:
                self._txinfo(transaction, "Open Round(s)", self.summary())

        if not self.args.disable_state_summaries and result.for_open_round:
            self._txinfo(transaction, "Transaction", self.summary())

        if result.for_open_round:
            self._txinfo(transaction, "Transaction Total", self.total_summary())

        return result

    def handle_root_event(self, transaction, event):
        if isinstance(event, ExercisedEvent):
            return self.handle_root_exercised_event(transaction, event)
        elif isinstance(event, CreatedEvent):
            return self.handle_root_created_event(transaction, event)
        else:
            self._fail(transaction, f"Unknown event: {event}")

    def handle_root_created_event(self, transaction, event):
        if event.template_id.qualified_name in self.args.ignore_root_create:
            if event.template_id.qualified_name in TemplateQualifiedNames.all_tracked:
                self.active_contracts[event.contract_id] = event
                if (
                    event.template_id.qualified_name
                    == TemplateQualifiedNames.app_reward_coupon
                ):
                    round_number = event.payload.get_sv_reward_coupon_round()
                    return HandleTransactionResult.for_open_round(round_number)
                else:
                    return HandleTransactionResult.empty()
            else:
                return HandleTransactionResult.empty()

        match event.template_id.qualified_name:
            case TemplateQualifiedNames.dso_bootstrap:
                pass
            case TemplateQualifiedNames.amulet_conversion_rate_feed:
                pass
            case _:
                self._fail(
                    transaction,
                    f"Unexpected root CreatedEvent: {event} for transaction: {transaction}",
                )
        return HandleTransactionResult.empty()

    def handle_receive_sv_reward_coupon(self, transaction, event):
        coupons = (
            event.exercise_result.get_receive_sv_reward_coupons_result_sv_reward_coupons()
        )
        # This can happen if the SV has weight 0
        if len(coupons) == 0:
            return HandleTransactionResult.empty()
        for coupon_cid in coupons:
            coupon = transaction.by_contract_id[coupon_cid]
            self.active_contracts[coupon_cid] = coupon

            sv = coupon.payload.get_sv_reward_coupon_sv()
            round_number = coupon.payload.get_sv_reward_coupon_round()
            beneficiary = coupon.payload.get_sv_reward_coupon_beneficiary()
            weight = coupon.payload.get_sv_reward_coupon_weight()

            self._txinfo(
                transaction,
                "SvActivityRecord",
                f"Sv activity recorded in round {round_number} for SV {sv} to beneficiary {beneficiary} with weight {weight}",
                parties=[sv],
            )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_receive_validator_faucet_coupon(self, transaction, event):
        coupon_cid = (
            event.exercise_result.get_receive_validator_faucet_coupon_result_coupon_cid()
        )
        coupon = transaction.by_contract_id[coupon_cid]
        self.active_contracts[coupon_cid] = coupon

        round_number = coupon.payload.get_validator_faucet_round()
        validator = coupon.payload.get_validator_faucet_validator()

        self._txinfo(
            transaction,
            "ValidatorLivenessActivityRecord",
            f"Validator liveness activity recorded in round {round_number} for validator {validator}",
            parties=[validator],
        )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_record_validator_liveness_activity_record(self, transaction, event):
        coupon_cid = (
            event.exercise_result.get_record_validator_liveness_activity_result_record_cid()
        )
        coupon = transaction.by_contract_id[coupon_cid]
        self.active_contracts[coupon_cid] = coupon

        round_number = coupon.payload.get_validator_liveness_activity_record_round()
        validator = coupon.payload.get_validator_liveness_activity_record_validator()

        self._txinfo(
            transaction,
            "ValidatorLivenessActivityRecord",
            f"Validator liveness activity recorded in round {round_number} for validator {validator}",
            parties=[validator],
        )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_transfer_inputs(
        self, transaction, sender, transfer_round_number, inputs
    ):
        app_rewards = {}
        validator_rewards = {}
        validator_faucets = {}
        validator_liveness_activity_records = {}
        sv_rewards = {}
        amulets = []
        for i in inputs:
            tag = i["tag"]
            value = i["value"]
            match tag:
                case "InputAppRewardCoupon":
                    cid = value.get_contract_id()
                    app_reward = self.active_contracts[cid]
                    round_number = app_reward.payload.get_app_reward_round()
                    app_rewards.setdefault(round_number, []).append(app_reward)
                    del self.active_contracts[cid]
                case "InputValidatorRewardCoupon":
                    cid = value.get_contract_id()
                    validator_reward = self.active_contracts[cid]
                    round_number = validator_reward.payload.get_validator_reward_round()
                    validator_rewards.setdefault(round_number, []).append(
                        validator_reward
                    )
                    del self.active_contracts[cid]
                case "InputSvRewardCoupon":
                    cid = value.get_contract_id()
                    sv_reward = self.active_contracts[cid]
                    round_number = sv_reward.payload.get_sv_reward_coupon_round()
                    sv_rewards.setdefault(round_number, []).append(sv_reward)
                    del self.active_contracts[cid]
                case "InputAmulet":
                    cid = value.get_contract_id()
                    amulet = self.active_contracts[cid]
                    amulets += [amulet]
                    del self.active_contracts[cid]
                case "ExtTransferInput":
                    cid = value.get_ext_transfer_input_validator_faucet_coupon()
                    if cid:
                        validator_faucet = self.active_contracts[cid]
                        round_number = (
                            validator_faucet.payload.get_validator_faucet_round()
                        )
                        del self.active_contracts[cid]
                        validator_faucets.setdefault(round_number, []).append(
                            validator_faucet
                        )
                case "InputValidatorLivenessActivityRecord":
                    cid = value.get_contract_id()
                    validator_liveness_activity_record = self.active_contracts[cid]
                    round_number = (
                        validator_liveness_activity_record.payload.get_validator_liveness_activity_record_round()
                    )
                    validator_liveness_activity_records.setdefault(
                        round_number, []
                    ).append(validator_liveness_activity_record)
                    del self.active_contracts[cid]
                case "InputUnclaimedActivityRecord":
                    cid = value.get_contract_id()
                    del self.active_contracts[cid]
                case "InputDevelopmentFundCoupon":
                    cid = value.get_contract_id()
                    del self.active_contracts[cid]
                case _:
                    self._fail(transaction, f"Unexpected transfer input: {tag}")
        return TransferInputs(
            app_rewards,
            validator_rewards,
            validator_faucets,
            validator_liveness_activity_records,
            sv_rewards,
            amulets,
            transfer_round_number,
            self.active_issuing_mining_rounds(),
        )

    def handle_transfer_outputs(
        self,
        transaction,
        event,
        sender,
        open_round,
        declared_outputs,
        output_amulet_cids,
        output_fees,
        sender_change_amulet_cid,
        sender_change_fee,
    ):
        assert len(output_amulet_cids) == len(output_fees)
        assert len(declared_outputs) == len(output_fees)
        assert event.template_id.qualified_name == TemplateQualifiedNames.amulet_rules
        assert event.choice_name == "AmuletRules_Transfer"
        outputs = []
        fee_amounts = []
        output_descriptions = []
        amulet_price = open_round.payload.get_open_mining_round_amulet_price()
        transfer_config_usd = open_round.payload.get_open_mining_transfer_config()
        transfer_config_cc = transfer_config_usd.scale(DamlDecimal(1) / amulet_price)
        for (declared_output_amount, receiver_fee_ratio), output, output_fee in zip(
            declared_outputs, output_amulet_cids, output_fees
        ):
            cid = output["value"].get_contract_id()
            output_event = transaction.by_contract_id[cid]
            self.active_contracts[cid] = output_event
            match output["tag"]:
                case "TransferResultLockedAmulet":
                    amulet = output_event.payload.get_locked_amulet_amulet()
                    owner = amulet.get_amulet_owner()
                    initial_amount = (
                        amulet.get_amulet_amount().get_expiring_amount_initial_amount()
                    )
                    lock = output_event.payload.get_locked_amulet_time_lock()
                    expires_at = lock.get_time_lock_expires_at()
                    lock_holders = lock.get_time_lock_holders()
                    output_descriptions += [
                        f"locked amulet with amount {initial_amount} owned by {owner}, locked to owner and {lock_holders} until {expires_at}"
                    ]
                case "TransferResultAmulet":
                    amulet = output_event.payload
                    owner = amulet.get_amulet_owner()
                    initial_amount = (
                        amulet.get_amulet_amount().get_expiring_amount_initial_amount()
                    )
                    output_descriptions += [
                        f"amulet with amount {initial_amount} owned by {owner}"
                    ]
                    # set to an empty list to simplify computations below
                    lock_holders = []
                case tag:
                    self._fail(transaction, f"Unexpected transfer output: {tag}")

            outputs += [
                TransferOutput(
                    initial_amount=initial_amount,
                    owner=owner,
                    lock_holders=lock_holders,
                )
            ]

            fee_amounts += [output_fee]
            receiver_fee = receiver_fee_ratio * output_fee
            sender_fee = output_fee - receiver_fee
            transfer_fee = (
                DamlDecimal(0)
                if sender == owner
                else transfer_config_cc.transfer_fee.charge(
                    declared_output_amount, event.template_id.package_id
                )
            )
            locking_fee = len(lock_holders) * transfer_config_cc.lock_holder_fee
            create_fee = transfer_config_cc.create_fee
            if output_fee != create_fee + transfer_fee + locking_fee:
                self._fail(
                    transaction,
                    f"Fees don't add up, expected: {output_fee} = {create_fee} + {transfer_fee} + {locking_fee}",
                )
            output_descriptions += ["  fees:"]
            output_descriptions += [f"    total: {output_fee}"]
            output_descriptions += [f"      base_transfer_fee: {create_fee}"]
            if transfer_fee > DamlDecimal(0):
                output_descriptions += [f"      transfer_fee: {transfer_fee}"]
            if locking_fee > DamlDecimal(0):
                output_descriptions += [f"      locking_fee: {locking_fee}"]
            output_descriptions += [f"    split between:"]
            output_descriptions += [f"      sender: {sender_fee}"]
            output_descriptions += [f"      receiver: {receiver_fee}"]
        if sender_change_amulet_cid:
            sender_change = transaction.by_contract_id[sender_change_amulet_cid]
            amount = (
                sender_change.payload.get_amulet_amount().get_expiring_amount_initial_amount()
            )
            self.active_contracts[sender_change_amulet_cid] = sender_change
            output_descriptions += [f"sender_change with amount {amount}"]
            outputs += [
                TransferOutput(initial_amount=amount, owner=sender, lock_holders=[])
            ]
        output_descriptions += [f"sender_change_fee: {sender_change_fee}"]
        fee_amounts += [sender_change_fee]
        return ("\n".join(output_descriptions), outputs, fee_amounts)

    def handle_transfer_rewards(self, transaction, child_event_ids):
        validator_reward_coupons = []
        app_reward_coupons = []
        for event_id in child_event_ids:
            event = transaction.events_by_id[event_id]
            if isinstance(event, CreatedEvent):
                match event.template_id.qualified_name:
                    case TemplateQualifiedNames.validator_reward_coupon:
                        self.active_contracts[event.contract_id] = event
                        validator_reward_coupons += [event]
                    case TemplateQualifiedNames.app_reward_coupon:
                        self.active_contracts[event.contract_id] = event
                        app_reward_coupons += [event]
                    case _:
                        pass
        return (validator_reward_coupons, app_reward_coupons)

    def handle_transfer(self, transaction, event, description="Transfer"):
        arg = event.exercise_argument.get_amulet_rules_transfer_transfer()

        sender = arg.get_transfer_sender()
        provider = arg.get_transfer_provider()
        inputs = arg.get_transfer_inputs()
        outputs = arg.get_transfer_outputs()

        declared_outputs = [
            (x.get_transfer_output_amount(), x.get_transfer_output_receiver_fee_ratio())
            for x in outputs
        ]

        res = event.exercise_result
        round_number = res.get_transfer_result_round()
        round_contract = self.get_open_mining_round_by_round_number(round_number)

        transfer_inputs = self.handle_transfer_inputs(
            transaction, sender, round_number, inputs
        )

        (
            inputs_description,
            effective_inputs,
            reward_cc_input,
            initial_amulet_cc_input,
            amulet_cc_input,
            all_inputs,
        ) = transfer_inputs.summary(event)
        output_amulets_cids = res.get_transfer_result_created_amulets()
        output_fees = res.get_transfer_result_output_fees()
        sender_change_amulet_cid = res.get_transfer_result_sender_change_amulet()
        sender_change_fee = res.get_transfer_result_sender_change_fee()
        (validator_reward_coupons, app_reward_coupons) = self.handle_transfer_rewards(
            transaction, event.child_event_ids
        )

        (outputs_description, all_outputs, fee_amounts) = self.handle_transfer_outputs(
            transaction,
            event,
            sender,
            round_contract,
            declared_outputs,
            output_amulets_cids,
            output_fees,
            sender_change_amulet_cid,
            sender_change_fee,
        )
        summary = TransferSummary(
            effective_inputs,
            all_outputs,
            fee_amounts,
        )
        if transfer_error := summary.get_error("transfer"):
            self._fail(transaction, f"Transfer error: {transfer_error}")
        activity_record_descriptions = []
        for validator_reward in validator_reward_coupons:
            amount = validator_reward.payload.get_validator_reward_amount()
            activity_record_descriptions += [
                f"validator activity record for {sender} with amount {amount}"
            ]
        for app_reward in app_reward_coupons:
            amount = app_reward.payload.get_app_reward_amount()
            featured = app_reward.payload.get_app_reward_featured()
            prefix = (
                "featured app activity record" if featured else "app activity record"
            )
            activity_record_descriptions += [
                f"{prefix} for {provider} with amount {amount}"
            ]

        interested_parties = (
            [sender, provider]
            + summary.get_all_owners()
            + summary.get_all_lock_holders()
        )

        self._txinfo(
            transaction,
            description,
            textwrap.dedent(
                f"""\
                         sender: {sender}
                         provider: {provider}
                         round: {round_number}
                         inputs:\n"""
            )
            + textwrap.indent(str(inputs_description), "    ")
            + "\noutputs:\n"
            + textwrap.indent(outputs_description, "    ")
            + (
                "\n  activity_records:\n"
                + textwrap.indent("\n".join(activity_record_descriptions), "    ")
                if activity_record_descriptions
                else ""
            ),
            parties=interested_parties,
        )
        self.total_burnt += summary.get_fees_total() + (
            initial_amulet_cc_input - amulet_cc_input
        )

        self._report_line(
            transaction,
            "TX",
            {
                "update_id": transaction.update_id,
                "event_id": event.event_id,
                "record_time": transaction.record_time,
                "provider": provider,
                "sender": sender,
                "round": round_number,
                "input_reward_cc_total": reward_cc_input,
                "input_amulet_cc_total": amulet_cc_input,
                "holding_fees_total": initial_amulet_cc_input - amulet_cc_input,
                "tx_fees_total": summary.get_fees_total(),
            },
            parties=interested_parties,
        )

        for i in all_inputs:
            self._report_line(
                transaction,
                "TXI",
                {
                    "update_id": transaction.update_id,
                    "event_id": event.event_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "source": i.source,
                    "round": i.round,
                    "initial_amount_cc": i.initial_amount if i.initial_amount else "",
                    "effective_amount_cc": i.effective_amount,
                    "holding_fees_total": (
                        (i.initial_amount - i.effective_amount)
                        if i.initial_amount
                        else DamlDecimal(0)
                    ),
                    "currency": "CC",
                },
                parties=interested_parties,
            )
            match i.source:
                case "validator_faucet_activity_record":
                    self.total_minted += i.effective_amount
                case "validator_activity_record":
                    self.total_minted += i.effective_amount
                case "unfeatured_app_activity_record":
                    self.total_minted += i.effective_amount
                case "featured_app_activity_record":
                    self.total_minted += i.effective_amount
                case "sv_activity_record":
                    self.total_minted += i.effective_amount
                case "amulet":
                    pass
                case _:
                    self._fail(transaction, f"Unknown input source: {i.source}")

        for o in all_outputs:
            self._report_line(
                transaction,
                "TXO",
                {
                    "update_id": transaction.update_id,
                    "event_id": event.event_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "owner": o.owner,
                    "currency": " CC",
                    "round": round_number,
                    "initial_amount_cc": o.initial_amount,
                    "lock_holders": ";".join(o.lock_holders),
                },
                parties=interested_parties,
            )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_tap(self, transaction, event):
        summary = event.exercise_result.get_tap_result_amulet_sum()
        cid = summary.get_amulet_summary_amulet()
        amulet = transaction.by_contract_id[cid]
        owner = amulet.payload.get_amulet_owner()
        amount = amulet.payload.get_amulet_amount().get_expiring_amount_initial_amount()
        round_number = (
            amulet.payload.get_amulet_amount().get_expiring_amount_created_at()
        )
        self.active_contracts[cid] = amulet

        self._txinfo(
            transaction,
            "Tap",
            f"{owner} tapped {amount} in round {round_number}",
            parties=[owner],
        )
        return HandleTransactionResult.for_open_round(round_number)

    def handle_mint(self, transaction, event):
        summary = event.exercise_result.get_mint_result_amulet_sum()
        cid = summary.get_amulet_summary_amulet()
        amulet = transaction.by_contract_id[cid]
        owner = amulet.payload.get_amulet_owner()
        amount = amulet.payload.get_amulet_amount().get_expiring_amount_initial_amount()
        round_number = (
            amulet.payload.get_amulet_amount().get_expiring_amount_created_at()
        )
        self.active_contracts[cid] = amulet
        self._txinfo(
            transaction,
            "Mint",
            f"{owner} minted {amount} in round {round_number}",
            parties=[owner],
        )
        return HandleTransactionResult.for_open_round(round_number)

    def handle_buy_member_traffic(self, transaction, event):
        arg = event.exercise_argument
        inputs = arg.get_buy_member_traffic_inputs()
        provider = arg.get_buy_member_traffic_provider()
        sender = provider  # True by construction
        member_id = arg.get_buy_member_traffic_member_id()
        synchronizer_id = arg.get_buy_member_traffic_synchronizer_id()
        migration_id = arg.get_buy_member_traffic_migration_id()
        traffic_amount = arg.get_buy_member_traffic_traffic_amount()
        res = event.exercise_result
        round_number = res.get_buy_member_traffic_result_round()
        transfer_inputs = self.handle_transfer_inputs(
            transaction, provider, round_number, inputs
        )
        (
            inputs_description,
            effective_inputs,
            reward_cc_input,
            initial_amulet_cc_input,
            amulet_cc_input,
            all_inputs,
        ) = transfer_inputs.summary(event)
        amulet_paid = res.get_buy_member_traffic_result_amulet_paid()
        sender_change_cid = res.get_buy_member_traffic_result_sender_change_amulet()
        transfer_summary = res.get_buy_member_traffic_result_transfer_summary()
        if sender_change_cid:
            sender_change = transaction.by_contract_id[sender_change_cid]
            self.active_contracts[sender_change_cid] = sender_change
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if (
                isinstance(event, CreatedEvent)
                and event.template_id.qualified_name
                == TemplateQualifiedNames.validator_reward_coupon
            ):
                self.active_contracts[event.contract_id] = event
                validator_reward = event
        validator_reward_amount = validator_reward.payload.get_validator_reward_amount()
        sender_change_amount = (
            transfer_summary.get_transfer_summary_sender_change_amount()
        )
        sender_change_fee = transfer_summary.get_transfer_summary_sender_change_fee()
        all_outputs = [
            TransferOutput(
                initial_amount=sender_change_amount, owner=sender, lock_holders=[]
            )
        ]
        summary = TransferSummary(
            effective_inputs,
            all_outputs,
            [sender_change_fee, amulet_paid],
        )

        if transfer_error := summary.get_error("buy_traffic"):
            self._fail(transaction, f"Buy Traffic Error: {transfer_error}")

        interested_parties = [member_id, provider]

        self._txinfo(
            transaction,
            "BuyTraffic",
            textwrap.dedent(
                f"""\
                     round: {round_number}
                     provider: {provider}
                     member_id: {member_id}
                     synchronizer_id: {synchronizer_id}
                     migration_id: {migration_id}
                     inputs:\n"""
            )
            + textwrap.indent(str(inputs_description), "    ")
            + "\n"
            + textwrap.indent(
                textwrap.dedent(
                    f"""\
                     traffic_amount_bytes: {traffic_amount}
                     burnt_as_part_of_purchase: {amulet_paid}
                     validator_activity_record for {provider} with amount {validator_reward_amount}
                     outputs:
                       sender_change_amount: {sender_change_amount}
                         fee: {sender_change_fee}"""
                ),
                "  ",
            ),
            parties=interested_parties,
        )

        self._report_line(
            transaction,
            "TX",
            {
                "update_id": transaction.update_id,
                "event_id": event.event_id,
                "record_time": transaction.record_time,
                "provider": provider,
                "sender": sender,
                "input_reward_cc_total": reward_cc_input,
                "input_amulet_cc_total": amulet_cc_input,
                "holding_fees_total": initial_amulet_cc_input - amulet_cc_input,
                "tx_fees_total": summary.get_fees_total(),
            },
            parties=interested_parties,
        )

        self.total_burnt += summary.get_fees_total() + (
            initial_amulet_cc_input - amulet_cc_input
        )

        for i in all_inputs:
            self._report_line(
                transaction,
                "TXI",
                {
                    "update_id": transaction.update_id,
                    "event_id": event.event_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "source": i.source,
                    "round": i.round,
                    "initial_amount_cc": i.initial_amount if i.initial_amount else "",
                    "effective_amount_cc": i.effective_amount,
                    "holding_fees_total": (
                        (i.initial_amount - i.effective_amount)
                        if i.initial_amount
                        else DamlDecimal(0)
                    ),
                    "currency": " CC",
                },
                parties=interested_parties,
            )

        for o in all_outputs:
            self._report_line(
                transaction,
                "TXO",
                {
                    "update_id": transaction.update_id,
                    "event_id": event.event_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "owner": o.owner,
                    "currency": "CC",
                    "round": round_number,
                    "initial_amount_cc": o.initial_amount,
                    "lock_holders": ";".join(o.lock_holders),
                },
                parties=interested_parties,
            )

        self._report_line(
            transaction,
            "TXBW",
            {
                "update_id": transaction.update_id,
                "event_id": event.event_id,
                "record_time": transaction.record_time,
                "provider": provider,
                "cc_burnt": amulet_paid,
                "traffic_bytes_bought": traffic_amount,
                "traffic_member_id": member_id,
            },
            parties=interested_parties,
        )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_create_external_party_setup_proposal(self, transaction, event):
        arg = event.exercise_argument
        res = event.exercise_result
        return self.handle_transfer_preapproval_purchase(
            transaction,
            event,
            inputs=arg.get_create_external_party_setup_proposal_inputs(),
            receiver=res.get_create_external_party_setup_proposal_user(),
            provider=res.get_create_external_party_setup_proposal_validator(),
            transfer_result=res.get_create_external_party_setup_proposal_result_transfer_result(),
            amulet_paid=res.get_create_external_party_setup_proposal_result_amulet_paid(),
            description="create_external_party_setup_proposal",
        )

    def handle_create_transfer_preapproval(self, transaction, event):
        arg = event.exercise_argument
        res = event.exercise_result
        return self.handle_transfer_preapproval_purchase(
            transaction,
            event,
            inputs=arg.get_create_transfer_preapproval_inputs(),
            receiver=arg.get_create_transfer_preapproval_receiver(),
            provider=arg.get_create_transfer_preapproval_provider(),
            transfer_result=res.get_create_transfer_preapproval_result_transfer_result(),
            amulet_paid=res.get_create_transfer_preapproval_result_amulet_paid(),
            description="create_transfer_preapproval",
        )

    def handle_renew_transfer_preapproval(self, transaction, event):
        arg = event.exercise_argument
        res = event.exercise_result
        return self.handle_transfer_preapproval_purchase(
            transaction,
            event,
            inputs=arg.get_renew_transfer_preapproval_inputs(),
            receiver=res.get_renew_transfer_preapproval_receiver(),
            provider=res.get_renew_transfer_preapproval_provider(),
            transfer_result=res.get_renew_transfer_preapproval_result_transfer_result(),
            amulet_paid=res.get_renew_transfer_preapproval_result_amulet_paid(),
            description="renew_transfer_preapproval",
        )

    def handle_transfer_preapproval_purchase(
        self,
        transaction,
        event,
        inputs,
        receiver,
        provider,
        transfer_result,
        amulet_paid,
        description,
    ):
        sender = provider  # True by construction
        round_number = transfer_result.get_transfer_result_round()
        transfer_inputs = self.handle_transfer_inputs(
            transaction, provider, round_number, inputs
        )
        (
            inputs_description,
            effective_inputs,
            reward_cc_input,
            initial_amulet_cc_input,
            amulet_cc_input,
            all_inputs,
        ) = transfer_inputs.summary(event)
        sender_change_cid = transfer_result.get_transfer_result_sender_change_amulet()
        output_fees = transfer_result.get_transfer_result_output_fees()
        if sender_change_cid:
            sender_change = transaction.by_contract_id[sender_change_cid]
            self.active_contracts[sender_change_cid] = sender_change
        validator_reward = None
        for i, event_id in enumerate(event.child_event_ids):
            event = transaction.events_by_id[event_id]
            LOG.debug(
                f'{i+1}. {event.__class__.__name__} {event.template_id} {"" if isinstance(event, CreatedEvent) else event.choice_name}'
            )
            if (
                isinstance(event, CreatedEvent)
                and event.template_id.qualified_name
                == TemplateQualifiedNames.validator_reward_coupon
            ):
                self.active_contracts[event.contract_id] = event
                validator_reward = event
        validator_reward_amount = validator_reward.payload.get_validator_reward_amount()
        sender_change_amount = (
            transfer_result.get_transfer_result_sender_change_amount()
        )
        sender_change_fee = transfer_result.get_transfer_result_sender_change_fee()
        all_outputs = [
            TransferOutput(
                initial_amount=sender_change_amount, owner=sender, lock_holders=[]
            )
        ]
        summary = TransferSummary(
            effective_inputs,
            all_outputs,
            output_fees + [sender_change_fee, amulet_paid],
        )
        if transfer_error := summary.get_error(description):
            msg_prefix = (
                " ".join(p.capitalize() for p in description.split("_")) + " Error"
            )
            self._fail(transaction, f"{msg_prefix}: {transfer_error}")

        interested_parties = [receiver, provider]

        operation = "".join(p.capitalize() for p in description.split("_"))
        self._txinfo(
            transaction,
            operation,
            textwrap.dedent(
                f"""\
                     round: {round_number}
                     provider: {provider}
                     receiver: {receiver}
                     inputs:\n"""
            )
            + textwrap.indent(str(inputs_description), "    ")
            + "\n"
            + textwrap.indent(
                textwrap.dedent(
                    f"""\
                     burnt_as_part_of_purchase: {amulet_paid}
                     validator_activity_record for {provider} with amount {validator_reward_amount}
                     outputs:
                       sender_change_amount: {sender_change_amount}
                         sender_change_fee: {sender_change_fee}
                     output_fees: {output_fees} """
                ),
                "  ",
            ),
            parties=interested_parties,
        )

        self._report_line(
            transaction,
            "TX",
            {
                "update_id": transaction.update_id,
                "record_time": transaction.record_time,
                "provider": provider,
                "sender": sender,
                "input_reward_cc_total": reward_cc_input,
                "input_amulet_cc_total": amulet_cc_input,
                "holding_fees_total": initial_amulet_cc_input - amulet_cc_input,
                "tx_fees_total": summary.get_fees_total(),
            },
            parties=interested_parties,
        )

        for i in all_inputs:
            self._report_line(
                transaction,
                "TXI",
                {
                    "update_id": transaction.update_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "source": i.source,
                    "round": i.round,
                    "initial_amount_cc": i.initial_amount if i.initial_amount else "",
                    "effective_amount_cc": i.effective_amount,
                    "holding_fees_total": (
                        (i.initial_amount - i.effective_amount)
                        if i.initial_amount
                        else DamlDecimal(0)
                    ),
                    "currency": " CC",
                },
                parties=interested_parties,
            )

        for o in all_outputs:
            self._report_line(
                transaction,
                "TXO",
                {
                    "update_id": transaction.update_id,
                    "record_time": transaction.record_time,
                    "provider": provider,
                    "sender": sender,
                    "owner": o.owner,
                    "currency": "CC",
                    "round": round_number,
                    "initial_amount_cc": o.initial_amount,
                    "lock_holders": ";".join(o.lock_holders),
                },
                parties=interested_parties,
            )

        self._report_line(
            transaction,
            "TXPA",
            {
                "update_id": transaction.update_id,
                "record_time": transaction.record_time,
                "provider": provider,
                "receiver": receiver,
                "cc_burnt": amulet_paid,
            },
            parties=interested_parties,
        )

        return HandleTransactionResult.for_open_round(round_number)

    def handle_transfer_preapproval_send(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                return self.handle_transfer(
                    transaction,
                    child_event,
                    "TransferPreapproval_Send",
                )
        raise Exception(
            f"Could not find the AmuletRules_Transfer child event for TransferPreapproval_Send: {transaction}"
        )

    def handle_transfer_command_send(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "TransferPreapproval_Send"
            ):
                return self.handle_transfer_preapproval_send(
                    transaction,
                    child_event,
                )
            # Or a direct call to AmuletRules_Transfer (when DSO doesn't see
            # TransferPreapproval_Send due to visibility projection)
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                return self.handle_transfer(
                    transaction, child_event, description="TransferCommand_Send"
                )
        # This can happen when the transfer failed and the contract just got archived.
        return HandleTransactionResult.empty()

    def handle_locked_amulet_unlock(self, transaction, event, log_prefix="Unlock"):
        summary = event.exercise_result.get_locked_amulet_unlock_result_amulet_sum()
        amulet_cid = summary.get_amulet_summary_amulet()
        round_number = summary.get_amulet_summary_round()
        amulet = transaction.by_contract_id[amulet_cid]
        owner = amulet.payload.get_amulet_owner()
        del self.active_contracts[event.contract_id]
        self.active_contracts[amulet_cid] = amulet
        formatted_amulet = self.format_amulet(amulet_cid, amulet)
        self._txinfo(
            transaction,
            log_prefix,
            f"Amulet {formatted_amulet} was unlocked",
            parties=[owner],
        )
        return HandleTransactionResult.for_open_round(round_number)

    def handle_locked_owner_expire_lock(
        self, transaction, event, log_prefix="ExpireUnlock"
    ):
        summary = (
            event.exercise_result.get_locked_amulet_owner_expire_lock_result_amulet_sum()
        )
        amulet_cid = summary.get_amulet_summary_amulet()
        round_number = summary.get_amulet_summary_round()
        amulet = transaction.by_contract_id[amulet_cid]
        del self.active_contracts[event.contract_id]
        self.active_contracts[amulet_cid] = amulet
        formatted_amulet = self.format_amulet(amulet_cid, amulet)
        self._txinfo(
            transaction,
            log_prefix,
            f"Amulet {formatted_amulet} was unlocked because lock expired",
        )
        return HandleTransactionResult.for_open_round(round_number)

    def handle_dso_rules_amulet_expire(self, transaction, event):
        contract_id = event.exercise_argument.get_dso_rules_amulet_expire_cid()
        amulet = self.active_contracts[contract_id]
        del self.active_contracts[contract_id]
        expire_summary = (
            event.exercise_result.get_dso_rules_amulet_expire_result_expire_sum()
        )
        formatted_amulet = self.format_amulet(contract_id, amulet)
        round_number = expire_summary.get_amulet_expire_summary_round()
        self._txinfo(
            transaction,
            "Dso_AmuletExpire",
            f"Amulet {formatted_amulet} expired in round {round_number}",
        )
        return HandleTransactionResult.for_open_round(round_number)

    def format_amulet(self, contract_id, amulet):
        expiring_amount = amulet.payload.get_amulet_amount()
        owner = amulet.payload.get_amulet_owner()
        initial_amount = expiring_amount.get_expiring_amount_initial_amount()
        created_at = expiring_amount.get_expiring_amount_created_at()
        rate_per_round = expiring_amount.get_expiring_amount_rate_per_round()
        return f"Amulet owner: {owner}, initial_amount: {initial_amount}, created_at: {created_at}, rate_per_round: {rate_per_round}"

    def handle_dso_rules_locked_amulet_expire(self, transaction, event):
        contract_id = event.exercise_argument.get_dso_rules_locked_amulet_expire_cid()
        lockedAmulet = self.active_contracts[contract_id]
        del self.active_contracts[contract_id]
        expire_summary = (
            event.exercise_result.get_dso_rules_locked_amulet_expire_result_expire_sum()
        )
        round_number = expire_summary.get_amulet_expire_summary_round()
        formatted_locked_amulet = self.format_locked_amulet(contract_id, lockedAmulet)
        self._txinfo(
            transaction,
            "Dso_LockedAmuletExpire",
            f"Locked Amulet {formatted_locked_amulet} expired in round {round_number}",
        )
        return HandleTransactionResult.for_open_round(round_number)

    def format_locked_amulet(self, contract_id, lockedAmulet):
        lock = lockedAmulet.payload.get_locked_amulet_time_lock()
        lock_holders = lock.get_time_lock_holders()
        expires_at = lock.get_time_lock_expires_at()
        amulet = lockedAmulet.payload.get_locked_amulet_amulet()
        owner = amulet.get_amulet_owner()
        expiring_amount = amulet.get_amulet_amount()
        initial_amount = expiring_amount.get_expiring_amount_initial_amount()
        created_at = expiring_amount.get_expiring_amount_created_at()
        rate_per_round = expiring_amount.get_expiring_amount_rate_per_round()
        return f"Locked Amulet lock_holders: {lock_holders}, expires_at: {expires_at}, owner: {owner}, initial_amount: {initial_amount}, created_at: {created_at}, rate_per_round: {rate_per_round}"

    def handle_dso_bootstrap(self, transaction, event):
        rounds = set()
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if (
                isinstance(event, ExercisedEvent)
                and event.choice_name == "AmuletRules_Bootstrap_Rounds"
            ):
                for event_id in event.child_event_ids:
                    event = transaction.events_by_id[event_id]
                    if (
                        isinstance(event, CreatedEvent)
                        and event.template_id.qualified_name
                        == TemplateQualifiedNames.open_mining_round
                    ):
                        round_number = event.payload.get_open_mining_round_round()
                        rounds.add(round_number)
                        self.active_contracts[event.contract_id] = event
        self._txinfo(transaction, "Bootstrap", "Bootstrapped initial state")
        return HandleTransactionResult.new_open_rounds(rounds).merge(
            HandleTransactionResult.for_open_round(min(rounds))
        )

    def handle_advance_open_mining_rounds(self, transaction, event):
        latest_round_cid = (
            event.exercise_argument.get_advance_open_mining_rounds_latest_round_cid()
        )
        latest_round = self.active_contracts[latest_round_cid]
        latest_round_number = latest_round.payload.get_open_mining_round_round()
        assert len(event.child_event_ids) == 1
        amulet_rules_event = transaction.events_by_id[event.child_event_ids[0]]
        for event_id in amulet_rules_event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if isinstance(event, CreatedEvent):
                match event.template_id.qualified_name:
                    case TemplateQualifiedNames.open_mining_round:
                        self.active_contracts[event.contract_id] = event
                    case TemplateQualifiedNames.summarizing_mining_round:
                        summarizing_round = (
                            event.payload.get_summarizing_mining_round_round()
                        )
                        self.active_contracts[event.contract_id] = event
                    case _:
                        pass
            elif isinstance(event, ExercisedEvent):
                if event.is_consuming:
                    match event.template_id.qualified_name:
                        case TemplateQualifiedNames.open_mining_round:
                            del self.active_contracts[event.contract_id]
                        case TemplateQualifiedNames.unclaimed_reward:
                            del self.active_contracts[event.contract_id]
                        case _:
                            pass
            else:
                self._fail(transaction, f"Unexpected event type: {event}")
        new_round = latest_round_number + 1
        if not self.args.hide_round_events:
            self._txinfo(
                transaction,
                "AdvanceOpenMiningRounds",
                f"Advanced OpenMiningRound, latest open mining round is now {new_round}",
            )
        return HandleTransactionResult.new_open_round(new_round).merge(
            HandleTransactionResult.for_open_round(summarizing_round)
        )

    def handle_mining_round_close(self, transaction, event):
        issuing_cid = event.exercise_argument.get_mining_round_close_issuing_round_cid()
        issuing_round = self.active_contracts[issuing_cid]
        round_number = issuing_round.payload.get_issuing_mining_round_round()
        del self.active_contracts[issuing_cid]
        if not self.args.hide_round_events:
            self._txinfo(
                transaction,
                "CloseIssuingMiningRound",
                f"Issuing mining round {round_number} is closed",
            )
        return HandleTransactionResult.new_closed_round(round_number)

    def handle_entry_collect_payment(self, transaction, event, renewal):
        ans_entry_context = self.active_contracts[event.contract_id]
        user = ans_entry_context.payload.get_ans_entry_context_user()
        name = ans_entry_context.payload.get_ans_entry_context_name()
        assert len(event.child_event_ids) == 1
        event = transaction.events_by_id[event.child_event_ids[0]]
        if renewal:
            assert event.choice_name == "AnsRules_CollectEntryRenewalPayment"
        else:
            assert event.choice_name == "AnsRules_CollectInitialEntryPayment"
        event = transaction.events_by_id[event.child_event_ids[0]]
        if renewal:
            assert event.choice_name == "SubscriptionPayment_Collect"
        else:
            assert event.choice_name == "SubscriptionInitialPayment_Collect"
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if (
                isinstance(event, ExercisedEvent)
                and event.choice_name == "LockedAmulet_Unlock"
            ):
                del self.active_contracts[event.contract_id]
                cid = (
                    event.exercise_result.get_locked_amulet_unlock_result_amulet_sum().get_amulet_summary_amulet()
                )
                amulet = transaction.by_contract_id[cid]
                # Not adding amulet to active_contracts because it gets archived immediately again.
            if (
                isinstance(event, ExercisedEvent)
                and event.choice_name == "AmuletRules_Transfer"
            ):
                transfer_event = event
            if (
                isinstance(event, CreatedEvent)
                and event.template_id.qualified_name
                == TemplateQualifiedNames.subscription_idle_state
            ):
                self.active_contracts[event.contract_id] = event
        event = transfer_event
        assert event.choice_name == "AmuletRules_Transfer"
        arg = event.exercise_argument.get_amulet_rules_transfer_transfer()
        sender = arg.get_transfer_sender()
        inputs = arg.get_transfer_inputs()
        res = event.exercise_result
        round_number = res.get_transfer_result_round()
        (validator_reward_coupons, app_reward_coupons) = self.handle_transfer_rewards(
            transaction, event.child_event_ids
        )
        assert len(validator_reward_coupons) == 1
        amount = amulet.payload.get_amulet_amount()
        assert amount.get_expiring_amount_created_at() == round_number
        if renewal:
            firstline = f"RenewAnsEntry: ANS entry {name} renewed for {user}"
        else:
            firstline = f"CreateAnsEntry: ANS entry {name} created for {user}"
        self._txinfo(
            transaction,
            firstline,
            textwrap.dedent(
                f"""\
                         round: {round_number}
                         burnt_locked_amulet: -{amount.get_expiring_amount_initial_amount()} created in round {round_number}
                         validator_activity_record for {sender} with amount {validator_reward_coupons[0].payload.get_validator_reward_amount()}"""
            ),
            parties=[user],
        )

        self.total_burnt += amount.get_expiring_amount_initial_amount()

        return HandleTransactionResult.for_open_round(round_number)

    def handle_execute_confirmed_action(self, transaction, event):
        action = event.exercise_argument.get_execute_confirmed_action_action()
        match action["tag"]:
            case "ARC_DsoRules":
                # None of these affect the state we care about atm.
                return HandleTransactionResult.empty()
            case "ARC_AmuletRules":
                amulet_rules_action = action[
                    "value"
                ].get_arc_amulet_rules_amulet_rules_action()
                # The last event is the actual choice, the previous ones are confirmations.
                amulet_rules_event = transaction.events_by_id[event.child_event_ids[-1]]
                arg = amulet_rules_event.exercise_argument
                res = amulet_rules_event.exercise_result
                match amulet_rules_action["tag"]:
                    case "CRARC_MiningRound_StartIssuing":
                        summarizing_mining_round_cid = (
                            arg.get_start_issuing_mining_round_cid()
                        )
                        issuing_mining_round_cid = (
                            res.get_start_issuing_result_issuing_round_cid()
                        )
                        issuing_mining_round = transaction.by_contract_id[
                            issuing_mining_round_cid
                        ]
                        round_number = (
                            issuing_mining_round.payload.get_issuing_mining_round_round()
                        )
                        del self.active_contracts[summarizing_mining_round_cid]
                        self.active_contracts[issuing_mining_round_cid] = (
                            issuing_mining_round
                        )
                        for event_id in amulet_rules_event.child_event_ids:
                            event = transaction.events_by_id[event_id]
                            match event.template_id.qualified_name:
                                case TemplateQualifiedNames.unclaimed_reward:
                                    if isinstance(event, CreatedEvent):
                                        self.active_contracts[event.contract_id] = event
                                    else:
                                        del self.active_contracts[event.contract_id]

                        if not self.args.hide_round_events:
                            self._txinfo(
                                transaction,
                                "StartIssuingMiningRound",
                                f"Mining round {round_number} moved to issuing phase",
                            )
                        return HandleTransactionResult.for_open_round(round_number)
                    case _:
                        return HandleTransactionResult.empty()
            case "ARC_AnsEntryContext":
                ans_entry_context_action = action[
                    "value"
                ].get_arc_ans_entry_context_ans_entry_context_action()
                match ans_entry_context_action["tag"]:
                    case "ANSRARC_CollectInitialEntryPayment":
                        # The last event is the actual choice, the previous ones are confirmations.
                        ans_entry_context_event = transaction.events_by_id[
                            event.child_event_ids[-1]
                        ]
                        return self.handle_entry_collect_payment(
                            transaction, ans_entry_context_event, renewal=False
                        )
                    case "ANSRARC_RejectEntryInitialPayment":
                        for event_id, unlock_event in transaction.events_by_id.items():
                            if (
                                isinstance(unlock_event, ExercisedEvent)
                                and unlock_event.choice_name == "LockedAmulet_Unlock"
                            ):
                                return self.handle_locked_amulet_unlock(
                                    transaction,
                                    unlock_event,
                                    log_prefix="Reject Initial Subscription Payment",
                                )
                    case tag:
                        LOG.error(f"Unexpected ARC_AnsEntryContext tag: {tag}")
            case _:
                self._fail(transaction, f"Unexpected action: {action}")

    def handle_unclaimed_reward_create_archive(self, transaction, event):
        for event_id in transaction.events_by_id:
            event = transaction.events_by_id[event_id]
            match event.template_id.qualified_name:
                case TemplateQualifiedNames.unclaimed_reward:
                    if isinstance(event, CreatedEvent):
                        self.active_contracts[event.contract_id] = event
                    else:
                        del self.active_contracts[event.contract_id]

        return HandleTransactionResult.empty()

    def handle_claim_expired_rewards(self, transaction, event):
        # We support both the new models where DsoRules_ClaimExpiredRewardCoupons directly archives
        # and the old models where it goes through AmuletRules_ClaimExpiredRewardCoupons
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_ClaimExpiredRewards"
            ):
                event = child_event
        rewards = []
        rewards_lines = []
        round = 0
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if isinstance(event, CreatedEvent):
                match event.template_id.qualified_name:
                    case TemplateQualifiedNames.unclaimed_reward:
                        self.active_contracts[event.contract_id] = event
            if isinstance(event, ExercisedEvent):
                cid = event.contract_id
                match event.choice_name:
                    case "ValidatorRewardCoupon_DsoExpire":
                        validator_reward_coupon = self.active_contracts[cid]
                        rewards += [validator_reward_coupon]
                        round = (
                            validator_reward_coupon.payload.get_validator_reward_round()
                        )
                        user = (
                            validator_reward_coupon.payload.get_validator_reward_user()
                        )
                        amount = (
                            validator_reward_coupon.payload.get_validator_reward_amount()
                        )
                        if _party_enabled(self.args, user):
                            rewards_lines += [
                                f"  validator_activity_record: user: {user}, amount: {amount}"
                            ]
                        del self.active_contracts[cid]
                    case "ValidatorFaucetCoupon_DsoExpire":
                        validator_faucet_coupon = self.active_contracts[cid]
                        rewards += [validator_faucet_coupon]
                        round = validator = (
                            validator_faucet_coupon.payload.get_validator_faucet_round()
                        )
                        validator = (
                            validator_faucet_coupon.payload.get_validator_faucet_validator()
                        )
                        if _party_enabled(self.args, validator):
                            rewards_lines += [
                                f"  validator_liveness_record: validator: {validator}"
                            ]
                        del self.active_contracts[cid]
                    case "ValidatorLivenessActivityRecord_DsoExpire":
                        validator_liveness_activity_record = self.active_contracts[cid]
                        rewards += [validator_liveness_activity_record]
                        round = validator = (
                            validator_liveness_activity_record.payload.get_validator_faucet_round()
                        )
                        validator = (
                            validator_liveness_activity_record.payload.get_validator_faucet_validator()
                        )
                        if _party_enabled(self.args, validator):
                            rewards_lines += [
                                f"  validator_liveness_record: validator: {validator}"
                            ]
                        del self.active_contracts[cid]
                    case "AppRewardCoupon_DsoExpire":
                        app_reward_coupon = self.active_contracts[cid]
                        rewards += [app_reward_coupon]
                        round = app_reward_coupon.payload.get_app_reward_round()
                        provider = app_reward_coupon.payload.get_app_reward_provider()
                        featured = (
                            "featured"
                            if app_reward_coupon.payload.get_app_reward_featured()
                            else "unfeatured"
                        )
                        amount = app_reward_coupon.payload.get_app_reward_amount()
                        if _party_enabled(self.args, provider):
                            rewards_lines += [
                                f"  {featured} app_activity_record: provider: {provider}, amount: {amount}"
                            ]
                        del self.active_contracts[cid]
                    case "SvRewardCoupon_DsoExpire":
                        sv_reward_coupon = self.active_contracts[cid]
                        rewards += [sv_reward_coupon]
                        round = sv_reward_coupon.payload.get_sv_reward_coupon_round()
                        sv = sv_reward_coupon.payload.get_sv_reward_coupon_sv()
                        beneficiary = (
                            sv_reward_coupon.payload.get_sv_reward_coupon_beneficiary()
                        )
                        weight = sv_reward_coupon.payload.get_sv_reward_coupon_weight()
                        if _party_enabled(self.args, sv) or _party_enabled(
                            self.args, beneficiary
                        ):
                            rewards_lines += [
                                f"  sv_activity_record: sv: {sv}, beneficiary: {beneficiary}, weight: {weight}"
                            ]
                        del self.active_contracts[cid]
                    case choice:
                        self._fail(
                            transaction,
                            f"Unexpected exercise as part of activity record expiry: {event}",
                        )
        if len(rewards_lines):
            expired_activity_rewards = "\n".join(rewards_lines)
            self._txinfo(
                transaction,
                "ExpireActivityRecords",
                f"Expired activity records for round {round}:\n{expired_activity_rewards}",
            )
        # Not returning a round means we don't print a summary afterward but that's fine.
        return HandleTransactionResult.empty()

    def handle_request_entry(self, transaction, event):
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if (
                event.template_id.qualified_name
                == TemplateQualifiedNames.ans_entry_context
            ):
                self.active_contracts[event.contract_id] = event
                entry_user = event.payload.get_ans_entry_context_user()
                entry_name = event.payload.get_ans_entry_context_name()
        self._txinfo(
            transaction,
            "AnsEntryRequest",
            f"Party {entry_user} requested ANS entry {entry_name}",
            parties=[entry_user],
        )
        return HandleTransactionResult.for_open_round(0)

    def handle_subscription_lock_amulet(
        self, transaction, event, subscription_request_cid, description
    ):
        ans_entry_context = self.get_ans_entry_context_by_subscription_request(
            subscription_request_cid
        )
        user = ans_entry_context.payload.get_ans_entry_context_user()
        name = ans_entry_context.payload.get_ans_entry_context_name()
        for event_id in event.child_event_ids:
            event = transaction.events_by_id[event_id]
            if event.choice_name == "AmuletRules_Transfer":
                return self.handle_transfer(
                    transaction,
                    event,
                    description(user, name),
                )

    def handle_subscription_request_accept_and_make_payment(self, transaction, event):
        return self.handle_subscription_lock_amulet(
            transaction,
            event,
            event.contract_id,
            lambda user, name: f"AnsEntryRequest_AcceptAndLock: {user} accepted subscription request for ANS entry {name}",
        )

    def handle_subscription_idle_state_make_payment(self, transaction, event):
        idle_state = self.active_contracts[event.contract_id]
        del self.active_contracts[event.contract_id]
        return self.handle_subscription_lock_amulet(
            transaction,
            event,
            idle_state.payload.get_subscription_idle_state_reference(),
            lambda user, name: f"AnsEntry_RequestRenewAndLock: {user} request renewal for ANS entry {name}",
        )

    def handle_dso_rules_collect_entry_renewal_payment(self, transaction, event):
        assert len(event.child_event_ids) == 1
        event = transaction.events_by_id[event.child_event_ids[0]]
        assert event.choice_name == "AnsEntryContext_CollectEntryRenewalPayment"
        return self.handle_entry_collect_payment(transaction, event, renewal=True)

    def handle_subscription_idle_state_cancel_subscription(self, transaction, event):
        idle_state = self.active_contracts[event.contract_id]
        del self.active_contracts[event.contract_id]
        reference = idle_state.payload.get_subscription_idle_state_reference()
        ans_entry_context = self.get_ans_entry_context_by_subscription_request(
            reference
        )
        user = ans_entry_context.payload.get_ans_entry_context_user()
        name = ans_entry_context.payload.get_ans_entry_context_name()
        self._txinfo(
            transaction,
            "AnsCancelSubscription",
            f"Subscription cancelled by user {user} for entry {name}",
            parties=[user],
        )
        # Not associated with any round so just return an empty result
        return HandleTransactionResult.empty()

    def handle_dso_rules_terminate_subscription(self, transaction, event):
        arg = event.exercise_argument
        ans_entry_context_cid = (
            arg.get_dso_rules_terminate_subscription_ans_entry_context_cid()
        )
        ans_entry_context = self.active_contracts[ans_entry_context_cid]
        user = ans_entry_context.payload.get_ans_entry_context_user()
        name = ans_entry_context.payload.get_ans_entry_context_name()
        del self.active_contracts[ans_entry_context_cid]
        self._txinfo(
            transaction,
            "AnsTerminateSubscription",
            f"Subscription terminated for user {user} and entry {name}",
            parties=[user],
        )
        # Not associated with any round so just return an empty result
        return HandleTransactionResult.empty()

    def handle_dso_rules_expire_subscription(self, transaction, event):
        arg = event.exercise_argument
        ans_entry_context_cid = (
            arg.get_dso_rules_expire_subscription_ans_entry_context_cid()
        )
        subscriptionIdleStateCid = (
            arg.get_dso_rules_expire_subscription_subscription_idle_state_cid()
        )
        ans_entry_context = self.active_contracts[ans_entry_context_cid]
        user = ans_entry_context.payload.get_ans_entry_context_user()
        name = ans_entry_context.payload.get_ans_entry_context_name()
        del self.active_contracts[ans_entry_context_cid]
        del self.active_contracts[subscriptionIdleStateCid]
        self._txinfo(
            transaction,
            "AnsExpireSubscription",
            f"Subscription expired for user {user} and entry {name}",
            parties=[user],
        )
        # Not associated with any round so just return an empty result
        return HandleTransactionResult.empty()

    def handle_convert_featured_app_activity_markers(self, transaction, event):
        assert len(event.child_event_ids) == 1
        event = transaction.events_by_id[event.child_event_ids[0]]
        assert event.choice_name == "AmuletRules_ConvertFeaturedAppActivityMarkers"
        open_round = self.active_contracts[
            event.exercise_argument.get_amuletrules_convertfeaturedappactivitymarkers_openroundcid()
        ]
        round_number = open_round.payload.get_open_mining_round_round()
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, CreatedEvent)
                and child_event.template_id.qualified_name
                == TemplateQualifiedNames.app_reward_coupon
            ):
                self.active_contracts[child_event.contract_id] = child_event
        return HandleTransactionResult.for_open_round(round_number)

    def handle_locked_input(self, child_event, transaction):
        if (
            isinstance(child_event, ExercisedEvent)
            and child_event.choice_name == "LockedAmulet_OwnerExpireLock"
        ):
            self.handle_locked_owner_expire_lock(
                transaction,
                child_event,
                log_prefix="Unlock transfer inputs with expired locks",
            )

    def handle_transfer_factory_transfer(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            self.handle_locked_input(child_event, transaction)
            # There is either a TransferPreapproval_Send
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "TransferPreapproval_Send"
            ):
                return self.handle_transfer_preapproval_send(transaction, child_event)
            # Or a direct call to AmuletRules_Transfer
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                output = event.exercise_result.get_transferinstructionresult_output()
                description = (
                    "Token standard: offer transfer"
                    if output["tag"] == "TransferInstructionResult_Pending"
                    else "Token standard: self-transfer"
                )
                return self.handle_transfer(
                    transaction, child_event, description=description
                )
        raise Exception(
            f"Could not find TransferPreapproval_Send or AmuletRules_Transfer child event for TransferFactory_Transfer: {transaction}"
        )

    def handle_transfer_instruction_accept(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: unlock funds for accepted transfer",
                )
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                return self.handle_transfer(
                    transaction,
                    child_event,
                    description="Token standard: accept transfer",
                )
        raise Exception(
            f"Could not find AmuletRules_Transfer child of TransferInstruction_Accept"
        )

    def handle_transfer_instruction_reject(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                return self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: offer rejected - return locked funds",
                )
        # Unlocking is skipped, if the LockedAmulet was already archived.
        return HandleTransactionResult.empty()

    def handle_transfer_instruction_withdraw(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                return self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: offer withdrawn - return locked funds",
                )
        # Unlocking is skipped, if the LockedAmulet was already archived.
        return HandleTransactionResult.empty()

    def handle_allocation_factory_allocate(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            self.handle_locked_input(child_event, transaction)
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                return self.handle_transfer(
                    transaction,
                    child_event,
                    description="Token standard: lock funds for allocation ",
                )
        raise Exception(
            f"Could not find AmuletRules_Transfer child of AllocationFactory_Allocate"
        )

    def handle_allocation_execute_transfer(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: unlock allocated funds",
                )
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "AmuletRules_Transfer"
            ):
                return self.handle_transfer(
                    transaction,
                    child_event,
                    description="Token standard: execute allocated transfer",
                )
        raise Exception(
            f"Could not find AmuletRules_Transfer child of AllocationFactory_Allocate"
        )

    def handle_allocation_withdraw(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                return self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: allocation withdrawn - return locked funds",
                )
        # Unlocking is skipped, if the LockedAmulet was already archived.
        return HandleTransactionResult.empty()

    def handle_allocation_cancel(self, transaction, event):
        for event_id in event.child_event_ids:
            child_event = transaction.events_by_id[event_id]
            if (
                isinstance(child_event, ExercisedEvent)
                and child_event.choice_name == "LockedAmulet_Unlock"
            ):
                return self.handle_locked_amulet_unlock(
                    transaction,
                    child_event,
                    log_prefix="Token standard: allocation cancelled - return locked funds",
                )
        # Unlocking is skipped, if the LockedAmulet was already archived.
        return HandleTransactionResult.empty()

    def handle_root_exercised_event(self, transaction, event):
        LOG.debug(f"Root exercise: {event.choice_name}")
        match event.choice_name:
            case "DsoRules_ReceiveSvRewardCoupon":
                return self.handle_receive_sv_reward_coupon(transaction, event)
            case "ValidatorLicense_ReceiveFaucetCoupon":
                return self.handle_receive_validator_faucet_coupon(transaction, event)
            case "ValidatorLicense_RecordValidatorLivenessActivity":
                return self.handle_record_validator_liveness_activity_record(
                    transaction, event
                )
            case "ValidatorLicense_UpdateMetadata":
                return HandleTransactionResult.empty()
            case "ValidatorLicense_ReportActive":
                return HandleTransactionResult.empty()
            case "AmuletRules_Transfer":
                return self.handle_transfer(transaction, event)
            case "AmuletRules_DevNet_Tap":
                return self.handle_tap(transaction, event)
            case "AmuletRules_Mint":
                return self.handle_mint(transaction, event)
            case "AmuletRules_BuyMemberTraffic":
                return self.handle_buy_member_traffic(transaction, event)
            case "AmuletRules_CreateExternalPartySetupProposal":
                return self.handle_create_external_party_setup_proposal(
                    transaction, event
                )
            case "ExternalPartySetupProposal_Accept":
                return HandleTransactionResult.empty()
            case "AmuletRules_CreateTransferPreapproval":
                return self.handle_create_transfer_preapproval(transaction, event)
            case "TransferPreapproval_Renew":
                return self.handle_renew_transfer_preapproval(transaction, event)
            case "TransferPreapproval_Send":
                return self.handle_transfer_preapproval_send(transaction, event)
            case "TransferPreapproval_Cancel":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireTransferPreapproval":
                return HandleTransactionResult.empty()
            case "TransferCommand_Send":
                return self.handle_transfer_command_send(transaction, event)
            case "LockedAmulet_Unlock":
                return self.handle_locked_amulet_unlock(transaction, event)
            case "LockedAmulet_OwnerExpireLock":
                return self.handle_locked_owner_expire_lock(transaction, event)
            case "DsoRules_Amulet_Expire":
                return self.handle_dso_rules_amulet_expire(transaction, event)
            case "DsoRules_LockedAmulet_ExpireAmulet":
                return self.handle_dso_rules_locked_amulet_expire(transaction, event)
            case "AnsRules_RequestEntry":
                return self.handle_request_entry(transaction, event)
            case "DsoRules_ExpireAnsEntry":
                return HandleTransactionResult.empty()
            case "SubscriptionRequest_AcceptAndMakePayment":
                return self.handle_subscription_request_accept_and_make_payment(
                    transaction, event
                )
            case "SubscriptionRequest_Reject":
                # No change to the tracked ACS: only creates a TerminatedSubscription.
                return HandleTransactionResult.empty()
            case "SubscriptionIdleState_MakePayment":
                return self.handle_subscription_idle_state_make_payment(
                    transaction, event
                )
            case "DsoRules_CollectEntryRenewalPayment":
                return self.handle_dso_rules_collect_entry_renewal_payment(
                    transaction, event
                )
            case "DsoRules_ExpireSubscription":
                return self.handle_dso_rules_expire_subscription(transaction, event)
            case "DsoRules_TerminateSubscription":
                return self.handle_dso_rules_terminate_subscription(transaction, event)
            case "SubscriptionIdleState_CancelSubscription":
                return self.handle_subscription_idle_state_cancel_subscription(
                    transaction, event
                )
            case "DsoRules_ClaimExpiredRewards":
                return self.handle_claim_expired_rewards(transaction, event)
            case "DsoRules_MergeUnclaimedRewards":
                return self.handle_unclaimed_reward_create_archive(transaction, event)
            case "DsoRules_AdvanceOpenMiningRounds":
                return self.handle_advance_open_mining_rounds(transaction, event)
            case "DsoRules_ExecuteConfirmedAction":
                return self.handle_execute_confirmed_action(transaction, event)
            case "DsoRules_MiningRound_Close":
                return self.handle_mining_round_close(transaction, event)
            case "DsoBootstrap_Bootstrap":
                return self.handle_dso_bootstrap(transaction, event)
            case "DsoRules_SubmitStatusReport":
                return HandleTransactionResult.empty()
            case "DsoRules_OnboardValidator":
                return HandleTransactionResult.empty()
            case "DsoRules_StartSvOnboarding":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireSvOnboardingRequest":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireSvOnboardingConfirmed":
                return HandleTransactionResult.empty()
            case "DsoRules_ConfirmAction":
                return HandleTransactionResult.empty()
            case "DsoRules_AddConfirmedSv":
                return HandleTransactionResult.empty()
            case "DsoRules_ArchiveSvOnboardingRequest":
                return HandleTransactionResult.empty()
            case "DsoRules_SetSynchronizerNodeConfig":
                return HandleTransactionResult.empty()
            case "DsoRules_UpdateAmuletPriceVote":
                return HandleTransactionResult.empty()
            case "DsoRules_AllocateUnallocatedUnclaimedActivityRecord":
                return self.handle_unclaimed_reward_create_archive(transaction, event)
            case "DsoRules_ExpireUnallocatedUnclaimedActivityRecord":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireUnclaimedActivityRecord":
                return self.handle_unclaimed_reward_create_archive(transaction, event)
            case "AmuletRules_Fetch":
                return HandleTransactionResult.empty()
            case "OpenMiningRound_Fetch":
                return HandleTransactionResult.empty()
            case "AmuletRules_ComputeFees":
                return HandleTransactionResult.empty()
            case "AmuletRules_DevNet_FeatureApp":
                return HandleTransactionResult.empty()
            case "FeaturedAppRight_Cancel":
                return HandleTransactionResult.empty()
            case "DsoRules_MergeMemberTrafficContracts":
                return HandleTransactionResult.empty()
            case "DsoRules_GarbageCollectAmuletPriceVotes":
                return HandleTransactionResult.empty()
            case "DsoRules_CastVote":
                return HandleTransactionResult.empty()
            case "DsoRules_ArchiveOutdatedElectionRequest":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireStaleConfirmation":
                return HandleTransactionResult.empty()
            case "DsoRules_RequestVote":
                return HandleTransactionResult.empty()
            case "DsoRules_CloseVoteRequest":
                return HandleTransactionResult.empty()
            case "DsoRules_RequestElection":
                return HandleTransactionResult.empty()
            case "DsoRules_ElectDsoDelegate":
                return HandleTransactionResult.empty()
            case "DsoRules_MergeSvRewardState":
                return HandleTransactionResult.empty()
            case "AmuletRules_AddFutureAmuletConfigSchedule":
                return HandleTransactionResult.empty()
            case "DsoRules_PruneAmuletConfigSchedule":
                return HandleTransactionResult.empty()
            case "DsoRules_MergeValidatorLicense":
                return HandleTransactionResult.empty()
            case "DsoRules_MergeUnclaimedDevelopmentFundCoupons":
                return HandleTransactionResult.empty()
            case "DsoRules_ExpireDevelopmentFundCoupon":
                return HandleTransactionResult.empty()
            case "AmuletRules_AllocateDevelopmentFundCoupon":
                return HandleTransactionResult.empty()
            case "DevelopmentFundCoupon_Withdraw":
                return HandleTransactionResult.empty()
            case "ExternalPartyAmuletRules_CreateTransferCommand":
                return HandleTransactionResult.empty()
            case "FeaturedAppRight_CreateActivityMarker":
                return HandleTransactionResult.empty()
            case "DsoRules_AmuletRules_ConvertFeaturedAppActivityMarkers":
                return self.handle_convert_featured_app_activity_markers(
                    transaction, event
                )
            case "TransferFactory_Transfer":
                return self.handle_transfer_factory_transfer(transaction, event)
            case "TransferFactory_PublicFetch":
                return HandleTransactionResult.empty()
            case "TransferInstruction_Accept":
                return self.handle_transfer_instruction_accept(transaction, event)
            case "TransferInstruction_Reject":
                return self.handle_transfer_instruction_reject(transaction, event)
            case "TransferInstruction_Withdraw":
                return self.handle_transfer_instruction_withdraw(transaction, event)
            # case "TransferInstruction_Update": -- intentionally not handled, as it is not used by Amulet
            case "AllocationFactory_Allocate":
                return self.handle_allocation_factory_allocate(transaction, event)
            case "AllocationFactory_PublicFetch":
                return HandleTransactionResult.empty()
            case "Allocation_ExecuteTransfer":
                return self.handle_allocation_execute_transfer(transaction, event)
            case "Allocation_Withdraw":
                return self.handle_allocation_withdraw(transaction, event)
            case "AmuletConversionRateFeed_Update":
                return HandleTransactionResult.empty()
            case "Allocation_Cancel":
                return self.handle_allocation_cancel(transaction, event)
            case "BatchedMarkersProxy_CreateMarkers":
                return HandleTransactionResult.empty()
            case "BatchedMarkersProxy_CreateMarkersV2":
                return HandleTransactionResult.empty()
            # case "AllocationInstruction_Withdraw": -- intentionally not handled, as it is not used by Amulet
            # case "AllocationInstruction_Update": -- intentionally not handled, as it is not used by Amulet
            # no handling of `AllocationRequest` choices as they are not visible to the DSO party
            case choice:
                choice_str = f"{event.template_id.qualified_name}:{choice}"

                if choice_str in self.args.ignore_root_exercise:
                    if (
                        event.is_consuming
                        and event.template_id.qualified_name
                        in TemplateQualifiedNames.all_tracked
                    ):
                        del self.active_contracts[event.contract_id]
                else:
                    self._fail(
                        transaction, f"Unexpected choice: {event.template_id}:{choice}"
                    )
                return HandleTransactionResult.empty()

    def get_per_party_balances(self):
        amulets = self.list_contracts(TemplateQualifiedNames.amulet)
        locked_amulets = self.list_contracts(TemplateQualifiedNames.locked_amulet)
        per_party_balances = {}
        for amulet in amulets.values():
            owner = amulet.payload.get_amulet_owner()
            per_party_balances.setdefault(owner, PerPartyBalance())
            per_party_balances[owner].amulets += [amulet]
        for locked_amulet in locked_amulets.values():
            amulet = locked_amulet.payload.get_locked_amulet_amulet()
            owner = amulet.get_amulet_owner()
            per_party_balances.setdefault(owner, PerPartyBalance())
            per_party_balances[owner].locked_amulets += [locked_amulet]
        return per_party_balances


@dataclass
class PerPartyBalance:
    amulets: list
    locked_amulets: list

    def __init__(self):
        self.amulets = []
        self.locked_amulets = []

    def __effective_for_round(self, round_number, amulet):
        amount = amulet.get_amulet_amount()
        created_at = amount.get_expiring_amount_created_at()
        assert round_number >= created_at
        round_diff = round_number + 1 - created_at
        return (
            amount.get_expiring_amount_initial_amount()
            - round_diff * amount.get_expiring_amount_rate_per_round()
        )

    def effective_for_round(self, round_number):
        total = DamlDecimal("0")
        for amulet in self.amulets:
            total += self.__effective_for_round(round_number, amulet.payload)
        for locked_amulet in self.locked_amulets:
            amulet = locked_amulet.payload.get_locked_amulet_amulet()
            total += self.__effective_for_round(round_number, amulet)
        # we deliberately cap the sum as opposed to each individual amulet to match scan
        return max(total, DamlDecimal("0"))

    # ignores holding fees
    def sum_amounts(self):
        total = DamlDecimal("0")
        for amulet in self.amulets:
            total += (
                amulet.payload.get_amulet_amount().get_expiring_amount_initial_amount()
            )
        for locked_amulet in self.locked_amulets:
            amulet = locked_amulet.payload.get_locked_amulet_amulet()
            total += amulet.get_amulet_amount().get_expiring_amount_initial_amount()
        return total


@dataclass
class AppState:
    state: State
    pagination_key: Optional[PaginationKey]

    @classmethod
    def empty(cls, args, csv_report):
        state = State(args, {}, None, None, csv_report, DamlDecimal(0), DamlDecimal(0))
        return cls(state, None)

    @classmethod
    def from_json(cls, args, data, csv_report):
        return cls(
            State.from_json(args, data["state"], csv_report),
            PaginationKey.from_json(data["pagination_key"]),
        )

    def to_json(self):
        return {
            "state": self.state.to_json(),
            "pagination_key": (
                None if self.pagination_key is None else self.pagination_key.to_json()
            ),
        }

    @classmethod
    def create_or_restore_from_cache(cls, args, csv_report):
        if args.cache_file_path is None:
            LOG.info(f"Caching disabled, creating new app state")
            return AppState.empty(args, csv_report)

        if args.rebuild_cache:
            LOG.info(f"Rebuilding cache, creating new app state")
            return AppState.empty(args, csv_report)

        if not os.path.exists(args.cache_file_path):
            LOG.info(
                f"File {args.cache_file_path} does not exist, creating new app state"
            )
            return AppState.empty(args, csv_report)

        try:
            with open(args.cache_file_path, "r") as file:
                data = json.load(file)
                LOG.info(f"Restoring app state from {args.cache_file_path}")
                return AppState.from_json(args, data, csv_report)

        except Exception as e:
            FAIL(f"Could not read app state from {args.cache_file_path}: {e}")

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
        self.state.flush_report()


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


def _parse_cli_args():
    # Parse command line arguments
    parser = argparse.ArgumentParser(
        description="Reads the update history from a given Splice Scan server."
    )
    parser.add_argument(
        "scan_url",
        help="Address of the Splice Scan server",
        default="http://localhost:5012",
    )
    parser.add_argument(
        "--ignore-root-create",
        action="append",
        default=[],
        help="Ignored root create in the form <TemplateQualifiedName>",
    )
    parser.add_argument(
        "--ignore-root-exercise",
        action="append",
        default=[],
        help="Ignored root exercise in the form <TemplateQualifiedName>:<Choice>",
    )
    parser.add_argument("--loglevel", help="Sets the log level", default="INFO")
    parser.add_argument(
        "--cache-file-path",
        help="File path to save application state to. "
        "If the file exists, processing will resume from the persisted state."
        "Otherwise, processing will start from beginning of the network.",
    )
    parser.add_argument(
        "--log-file-path",
        default="log/scan_txlog.log",
        help="File path to save application log to. "
        "If the file exists, processing will append to file.",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=100,
        help="Number of transactions to fetch per network request",
    )
    parser.add_argument(
        "--rebuild-cache",
        action="store_true",
        help="Force the cache to be rebuilt from scratch.",
    )
    parser.add_argument(
        "--stop-on-error",
        action="store_true",
        help="Stop processing when an error is encountered, and make no attempt to recover and continue.",
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
        "--hide-round-events",
        action="store_true",
        help="Suppress display of round specific events.",
    )
    parser.add_argument(
        "--hide-details",
        action="store_true",
        help="Show summary lines only, hiding details.",
    )
    parser.add_argument(
        "--disable-state-summaries",
        action="store_true",
        help="Suppress display the state summaries logged after each transaction.",
    )
    parser.add_argument(
        "--report-output",
        help="The name of a file to which a CSV report stream should be written. (Specific report structure a work in progress)",
    )
    parser.add_argument(
        "--append-to-report",
        action="store_true",
        help="Appends to the report file if enabled.",
    )
    parser.add_argument(
        "--stop-at-record-time",
        help="The script will stop once the it reaches the given record time. Expected in ISO format.",
    )
    parser.add_argument(
        "--compare-acs-with-snapshot",
        help="Compares the ACS at the end of the script with the ACS snapshot of the given record_time",
    )
    parser.add_argument(
        "--compare-balances-with-total-supply",
        help="Whether to compare the balances with those computed in the Token Standard 'getInstrument' endpoint",
        action="store_true",
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


async def _process_transaction(args, app_state, scan_client, transaction):
    LOG.debug(
        f"Processing transaction {transaction.update_id} at ({transaction.migration_id}, {transaction.record_time})"
    )

    app_state.state.handle_transaction(transaction)


async def main():
    global file_handler, LOG
    args = _parse_cli_args()

    LOG = _setup_logger("global", args.loglevel.upper(), args.log_file_path)
    _log_uncaught_exceptions()

    LOG.debug(f"Starting scan_txlog with arguments: {args}")

    report_stream = None
    try:
        if args.report_output:
            report_exists = os.path.isfile(args.report_output)

            mode = "x"
            if args.append_to_report:

                if report_exists:
                    mode = "a"

                if args.cache_file_path is None or args.rebuild_cache:
                    FAIL(
                        "Cannot sensibly use --append-to-report without cached state to "
                        "record previous progress through the event stream."
                    )

            elif report_exists:
                FAIL(f"Report file already exists: {args.report_output}")

            report_stream = open(args.report_output, mode, buffering=1)
        else:
            report_exists = False

        app_state = AppState.create_or_restore_from_cache(
            args, CSVReport(report_stream, not report_exists)
        )

        begin_t = time.time()
        tx_count = 0

        async with aiohttp.ClientSession() as session:
            scan_client = ScanClient(session, args.scan_url, args.page_size)
            stop_at_record_time = (
                datetime.fromisoformat(args.stop_at_record_time)
                if args.stop_at_record_time
                else None
            )
            last_migration_id = None
            while True:
                json_batch = await scan_client.updates(app_state.pagination_key)
                batch = [
                    TransactionTree.parse(tx)
                    for tx in json_batch
                    if (stop_at_record_time is None)
                    or (
                        datetime.fromisoformat(tx["record_time"]) <= stop_at_record_time
                    )
                ]
                LOG.debug(
                    f"Processing batch of size {len(batch)} starting at {app_state.pagination_key}"
                )

                for transaction in batch:
                    await _process_transaction(
                        args, app_state, scan_client, transaction
                    )
                    tx_count = tx_count + 1

                if len(batch) >= 1:
                    last = batch[-1]
                    app_state.pagination_key = PaginationKey(
                        last.migration_id, last.record_time.isoformat()
                    )
                    app_state.finalize_batch(args)
                    last_migration_id = last.migration_id
                if len(batch) < scan_client.page_size:
                    LOG.debug(f"Reached end of stream at {app_state.pagination_key}")
                    break
            if (
                args.compare_acs_with_snapshot is not None
                and last_migration_id is not None
            ):
                snapshot_time = datetime.fromisoformat(args.compare_acs_with_snapshot)
                after = None
                expected_contracts = app_state.state.active_contracts
                found_in_snapshot = {}
                while True:
                    snapshot_page = await scan_client.get_acs_snapshot_page_at(
                        last_migration_id,
                        snapshot_time,
                        TemplateQualifiedNames.all_tracked_with_package_name,
                        after,
                    )
                    for event in snapshot_page["created_events"]:
                        cid = event["contract_id"]
                        found_in_snapshot[cid] = event

                    if snapshot_page["next_page_token"] is None:
                        break
                    else:
                        after = snapshot_page["next_page_token"]
                missing_in_snapshot = set(expected_contracts.keys()).difference(
                    set(found_in_snapshot.keys())
                )
                missing_in_script = set(found_in_snapshot.keys()).difference(
                    set(expected_contracts.keys())
                )
                if len(missing_in_snapshot) > 0:
                    missing = [expected_contracts[cid] for cid in missing_in_snapshot]
                    LOG.error(f"Contracts missing in snapshot: {missing}")
                if len(missing_in_script) > 0:
                    missing = [found_in_snapshot[cid] for cid in missing_in_script]
                    LOG.error(f"Contracts missing in script ACS: {missing}")
                if args.compare_balances_with_total_supply:
                    # this will only work if a snapshot was taken, which is guaranteed by compare_acs_with_snapshot=True
                    token_metadata = await scan_client.get_amulet_token_metadata()
                    latest_per_party_balances = (
                        app_state.state.get_per_party_balances().values()
                    )
                    # sum up all balances
                    total_balance = sum(
                        [p.sum_amounts() for p in latest_per_party_balances],
                        DamlDecimal(0),
                    )
                    if DamlDecimal(token_metadata["totalSupply"]) != total_balance:
                        LOG.error(
                            f"Total supply mismatch: {token_metadata['totalSupply']} in metadata (as of {token_metadata['totalSupplyAsOf']}), {total_balance} in computed balances (as of {app_state.state.record_time})"
                        )

        duration = time.time() - begin_t
        LOG.info(
            f"End run. ({duration:.2f} sec., {tx_count} transaction(s), {scan_client.call_count} Scan API call(s), {scan_client.retry_count} retries)"
        )
    finally:
        if report_stream:
            report_stream.close()


if __name__ == "__main__":
    asyncio.run(main())
