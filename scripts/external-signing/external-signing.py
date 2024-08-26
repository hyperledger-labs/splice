#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import aiohttp
import argparse
import asyncio
import base64
import colorlog
from Crypto.Hash import SHA256
from Crypto.PublicKey import ECC, RSA
from Crypto.Signature import eddsa
from dataclasses import dataclass
import json
import os

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


async def session_post(session, url, payload):
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
            f"{self.url}/api/validator/v0/external-party-topology/generate",
            payload,
        )
        return await response.json()

    async def submit_external_party_topology(
        self, party_hint, signed_topology_txs, public_key
    ):
        payload = {
            "party_hint": party_hint,
            "signed_topology_txs": signed_topology_txs,
            "public_key": public_key,
        }
        response = await session_post(
            self.session,
            f"{self.url}/api/validator/v0/external-party-topology/submit",
            payload,
        )
        print(await response.text())


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

    signer = eddsa.new(private_key, "rfc8032")

    signed_txs = [
        {
            "topology_tx": tx["topology_tx"],
            "signed_hash": signer.sign(bytes.fromhex(tx["hash"])).hex(),
        }
        for tx in txs
    ]

    await validator_client.submit_external_party_topology(
        args.party_hint,
        signed_txs,
        public_key_hex,
    )


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
