#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import aiohttp
import argparse
import asyncio
import colorlog
import datetime
from Crypto.PublicKey import ECC
from Crypto.Signature import eddsa
from dataclasses import dataclass
import os
import uuid

cli_log_handler = colorlog.StreamHandler()
cli_log_handler.setFormatter(
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

logger = colorlog.getLogger("external-signing")
logger.addHandler(cli_log_handler)
logger.setLevel("DEBUG")


@dataclass
class HttpException(Exception):
    error: str
    cause: aiohttp.client_exceptions.ClientResponseError


async def session_post(session: aiohttp.ClientSession, url, payload):
    response = await session.post(url, json=payload)
    err = await response.text()
    try:
        response.raise_for_status()
    except aiohttp.client_exceptions.ClientResponseError as cause:
        raise HttpException(err, cause) from None
    return response


@dataclass
class ValidatorClient:
    session: aiohttp.ClientSession
    url: str

    async def generate_external_party_topology(self, party_hint, public_key):
        payload = {"party_hint": party_hint, "public_key": public_key}
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/topology/generate",
            payload,
        )
        return await response.json()

    async def submit_external_party_topology(self, signed_topology_txs, public_key):
        payload = {
            "signed_topology_txs": signed_topology_txs,
            "public_key": public_key,
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/topology/submit",
            payload,
        )
        return await response.json()

    async def create_external_party_setup_proposal(self, party_id):
        payload = {
            "user_party_id": party_id,
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/setup-proposal",
            payload,
        )
        return await response.json()

    async def prepare_external_party_setup_proposal_accept(self, contract_id, party_id):
        payload = {
            "contract_id": contract_id,
            "user_party_id": party_id,
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/setup-proposal/prepare-accept",
            payload,
        )
        return await response.json()

    async def submit_external_party_setup_proposal_accept(
        self, party_id, transaction, signed_tx_hash, public_key
    ):
        payload = {
            "submission": {
                "party_id": party_id,
                "transaction": transaction,
                "signed_tx_hash": signed_tx_hash,
                "public_key": public_key,
            }
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/setup-proposal/submit-accept",
            payload,
        )
        return await response.json()

    async def prepare_transfer_preapproval_send(
        self,
        sender_party_id,
        receiver_party_id,
        amount,
        expires_at,
        nonce,
    ):
        payload = {
            "sender_party_id": sender_party_id,
            "receiver_party_id": receiver_party_id,
            "amount": amount,
            "expires_at": expires_at,
            "nonce": nonce,
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/transfer-preapproval/prepare-send",
            payload,
        )
        return await response.json()

    async def submit_transfer_preapproval_send(
        self, party_id, transaction, signed_tx_hash, public_key
    ):
        payload = {
            "submission": {
                "party_id": party_id,
                "transaction": transaction,
                "signed_tx_hash": signed_tx_hash,
                "public_key": public_key,
            }
        }
        await session_post(
            self.session,
            f"{self.url}/api/validator/v0/admin/external-party/transfer-preapproval/submit-send",
            payload,
        )


async def handle_generate_key_pair(args, validator_client):
    private_key = ECC.generate(curve="ed25519")
    public_key = private_key.public_key()
    [private_key_file, public_key_file] = key_names(args.key_directory, args.key_name)
    with open(private_key_file, "wb") as f:
        data = private_key.export_key(format="DER")
        f.write(data)
        logger.debug(f"Wrote private key to {private_key_file}")
    with open(public_key_file, "wb") as f:
        data = public_key.export_key(format="DER")
        f.write(data)
        logger.debug(f"Wrote public key to {public_key_file}")


def key_names(key_directory, key_name):
    private_key_file = f"{key_directory}/{key_name}.priv"
    public_key_file = f"{key_directory}/{key_name}.pub"
    return [private_key_file, public_key_file]


def read_key_pair(key_directory, key_name):
    [private_key_file, public_key_file] = key_names(key_directory, key_name)
    with open(private_key_file, "rb") as f:
        data = f.read()
        private_key = ECC.import_key(data)
    with open(public_key_file, "rb") as f:
        data = f.read()
        public_key = ECC.import_key(data)
    return [private_key, public_key]


async def handle_setup_party(args, validator_client):
    logger.debug(f"Setting up party {args.party_hint} with key {args.key_name}")
    [private_key, public_key] = read_key_pair(args.key_directory, args.key_name)
    public_key_hex = public_key.export_key(format="raw").hex()
    response = await validator_client.generate_external_party_topology(
        args.party_hint, public_key_hex
    )
    txs = response["topology_txs"]
    prepared_party_id = response["party_id"]

    signer = eddsa.new(private_key, "rfc8032")

    signed_txs = [
        {
            "topology_tx": tx["topology_tx"],
            "signed_hash": signer.sign(bytes.fromhex(tx["hash"])).hex(),
        }
        for tx in txs
    ]

    response = await validator_client.submit_external_party_topology(
        signed_txs,
        public_key_hex,
    )
    party_id = response["party_id"]
    assert party_id == prepared_party_id
    logger.debug(f"Completed party setup, party id is: {party_id}")


async def poll_for_transfer_preapproval(
    party_id, validator_client, timeout=30, poll_interval=1
):
    logger.debug("Waiting for validator automation to create transfer preapproval")
    async with asyncio.timeout(timeout):
        while True:
            try:
                response = await validator_client.lookup_transfer_preapproval(party_id)
            except HttpException:
                logger.debug("Still waiting...")
                await asyncio.sleep(poll_interval)
            else:
                return response


async def handle_setup_transfer_preapproval(args, validator_client):
    logger.debug(f"Setting up TransferPreapproval for {args.party_id}")
    [private_key, public_key] = read_key_pair(args.key_directory, args.key_name)
    public_key_hex = public_key.export_key(format="raw").hex()
    response = await validator_client.create_external_party_setup_proposal(
        args.party_id
    )
    contract_id = response["contract_id"]
    response = await validator_client.prepare_external_party_setup_proposal_accept(
        contract_id, args.party_id
    )
    signer = eddsa.new(private_key, "rfc8032")
    signed_hash = signer.sign(bytes.fromhex(response["tx_hash"])).hex()
    response = await validator_client.submit_external_party_setup_proposal_accept(
        args.party_id, response["transaction"], signed_hash, public_key_hex
    )
    logger.debug(
        f"Created transfer preapproval with contract id {response['transfer_preapproval_contract_id']}"
    )


async def handle_transfer_preapproval_send(args, validator_client):
    logger.debug(
        f"Exercise choice TransferPreapproval_Send to transfer {args.amount} \
        from {args.sender_party_id} to {args.receiver_party_id}"
    )
    [private_key, public_key] = read_key_pair(args.key_directory, args.key_name)
    public_key_hex = public_key.export_key(format="raw").hex()
    expires_at = (
        f"{(datetime.datetime.now() + datetime.timedelta(hours=24)).isoformat()}Z"
    )
    response = await validator_client.prepare_transfer_preapproval_send(
        args.sender_party_id,
        args.receiver_party_id,
        args.amount,
        expires_at,
        args.nonce,
    )
    signer = eddsa.new(private_key, "rfc8032")
    signed_hash = signer.sign(bytes.fromhex(response["tx_hash"])).hex()
    await validator_client.submit_transfer_preapproval_send(
        args.sender_party_id, response["transaction"], signed_hash, public_key_hex
    )
    logger.debug("Transfer complete.")


def parse_cli_args():
    parser = argparse.ArgumentParser(
        description="Utility script to interact with the external signing part of the Validator API"
    )
    subparsers = parser.add_subparsers(required=True)

    parser.add_argument(
        "--validator-url",
        help="Address of Validator API",
        required=True,
    )

    parser_generate_key_pair = subparsers.add_parser(
        "generate-key-pair", help="Generate a new key pair"
    )
    parser_generate_key_pair.set_defaults(handler=handle_generate_key_pair)
    parser_generate_key_pair.add_argument("--key-directory", required=True)
    parser_generate_key_pair.add_argument("--key-name", required=True)

    parser_setup_party = subparsers.add_parser(
        "setup-party", help="Setup a new externally-hosted party"
    )
    parser_setup_party.set_defaults(handler=handle_setup_party)
    parser_setup_party.add_argument("--party-hint", required=True)
    parser_setup_party.add_argument("--key-directory", required=True)
    parser_setup_party.add_argument("--key-name", required=True)

    parser_setup_transfer_preapproval = subparsers.add_parser(
        "setup-transfer-preapproval",
        help="Setup the TransferPreapproval contract for an externally-hosted party",
    )
    parser_setup_transfer_preapproval.set_defaults(
        handler=handle_setup_transfer_preapproval
    )
    parser_setup_transfer_preapproval.add_argument("--party-id", required=True)
    parser_setup_transfer_preapproval.add_argument("--key-directory", required=True)
    parser_setup_transfer_preapproval.add_argument("--key-name", required=True)

    parser_transfer_preapproval_send = subparsers.add_parser(
        "transfer-preapproval-send",
        help="Initiate a pre-approved transfer",
    )
    parser_transfer_preapproval_send.set_defaults(
        handler=handle_transfer_preapproval_send
    )
    parser_transfer_preapproval_send.add_argument("--sender-party-id", required=True)
    parser_transfer_preapproval_send.add_argument("--receiver-party-id", required=True)
    parser_transfer_preapproval_send.add_argument("--amount", required=True)
    parser_transfer_preapproval_send.add_argument("--nonce", required=True)
    parser_transfer_preapproval_send.add_argument("--key-directory", required=True)
    parser_transfer_preapproval_send.add_argument("--key-name", required=True)

    return parser.parse_args()


async def main():
    args = parse_cli_args()

    token = os.environ["VALIDATOR_JWT_TOKEN"]

    headers = {
        "Authorization": f"Bearer {token}",
    }

    async with aiohttp.ClientSession(headers=headers) as session:
        validator_client = ValidatorClient(session, args.validator_url)
        await args.handler(args, validator_client)


if __name__ == "__main__":
    asyncio.run(main())
