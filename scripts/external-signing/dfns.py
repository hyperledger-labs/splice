#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import aiohttp
import argparse
import asyncio
import base64
import colorlog
from Crypto.Hash import SHA256
from Crypto.PublicKey import RSA
from Crypto.Signature import pkcs1_15
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

logger = colorlog.getLogger("dfns")
logger.addHandler(cli_log_handler)
logger.setLevel("DEBUG")


@dataclass
class DfnsClient:
    session: aiohttp.ClientSession
    url: str
    cred_id: str
    priv_key: str

    async def get_wallet(self, wallet_id):
        response = await self.session.get(
            f"{self.url}/wallets/{wallet_id}",
        )
        response.raise_for_status()
        return await response.json()

    async def init_action(
        self, user_action_payload, user_action_method, user_action_path
    ):
        payload = {
            "userActionPayload": json.dumps(user_action_payload, separators=(",", ":")),
            "userActionHttpMethod": user_action_method,
            "userActionHttpPath": user_action_path,
        }
        response = await self.session.post(f"{self.url}/auth/action/init", json=payload)
        response.raise_for_status()
        return await response.json()

    async def auth_action(
        self,
        challenge_identifier,
        challenge,
    ):
        client_data = json.dumps(
            {"type": "key.get", "challenge": challenge}, separators=(",", ":")
        ).encode("utf-8")
        signature = pkcs1_15.new(self.priv_key).sign(SHA256.new(client_data))
        payload = {
            "challengeIdentifier": challenge_identifier,
            "firstFactor": {
                "kind": "Key",
                "credentialAssertion": {
                    "credId": self.cred_id,
                    "clientData": base64.urlsafe_b64encode(client_data).decode("utf-8"),
                    "signature": base64.urlsafe_b64encode(signature).decode("utf-8"),
                },
            },
        }
        response = await self.session.post(f"{self.url}/auth/action", json=payload)
        response.raise_for_status()
        return await response.json()

    async def user_action(self, path, payload):
        result = await self.init_action(payload, "POST", path)
        result = await self.auth_action(
            result["challengeIdentifier"], result["challenge"]
        )
        response = await self.session.post(
            f"{self.url}{path}",
            json=payload,
            headers={
                "x-dfns-useraction": result["userAction"],
                "content-type": "application/json",
            },
        )
        response.raise_for_status()
        return await response.json()

    async def sign(self, wallet_id, message):
        return await self.user_action(
            f"/wallets/{wallet_id}/signatures",
            {
                "kind": "Message",
                "message": message,
            },
        )


async def handle_get_wallet_public_key(args, dfns_client):
    response = await dfns_client.get_wallet(args.wallet_id)
    print(response["signingKey"]["publicKey"])


async def handle_sign(args, dfns_client):
    response = await dfns_client.sign(args.wallet_id, args.message)
    r = response["signature"]["r"]
    s = response["signature"]["s"]
    print(f"""{r.removeprefix("0x")}{s.removeprefix("0x")}""")


def parse_cli_args():
    parser = argparse.ArgumentParser(
        description="Utility script to interact with the DFNS API"
    )
    parser.add_argument(
        "--dfns_url",
        help="Address of the DFNS API",
        default="https://api.dfns.ninja",
    )
    subparsers = parser.add_subparsers(required=True)

    parser_get_wallet_public_key = subparsers.add_parser(
        "wallet-public-key", help="Retrieve the hex-encoded public key of a wallet"
    )
    parser_get_wallet_public_key.set_defaults(handler=handle_get_wallet_public_key)
    parser_get_wallet_public_key.add_argument("--wallet-id", required=True)

    parser_sign = subparsers.add_parser(
        "sign",
        help="Sign the given hex string using the private key of the given wallet",
    )
    parser_sign.set_defaults(handler=handle_sign)
    parser_sign.add_argument("--wallet-id", required=True)
    parser_sign.add_argument("--message", required=True)

    return parser.parse_args()


async def main():
    args = parse_cli_args()

    token = os.environ["DFNS_TOKEN"]
    app_id = os.environ["DFNS_APP_ID"]
    cred_id = os.environ["DFNS_CRED_ID"]
    priv_key = RSA.import_key(os.environ["DFNS_PRIVATE_KEY"])

    headers = {
        "Authorization": f"Bearer {token}",
        "X-DFNS-APPID": app_id,
    }

    async with aiohttp.ClientSession(headers=headers) as session:
        dfns_client = DfnsClient(session, args.dfns_url, cred_id, priv_key)
        await args.handler(args, dfns_client)


if __name__ == "__main__":
    asyncio.run(main())
