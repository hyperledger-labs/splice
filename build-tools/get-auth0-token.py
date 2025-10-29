#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
from auth0.authentication import GetToken
from auth0.management import Auth0
import datetime
from kubernetes import client, config
from kubernetes.client.rest import ApiException
import os
import subprocess
import base64
import json
import jsonpickle
import sys
import re

MIN_EXPIRATION_DELTA = datetime.timedelta(days=2)
CACHE_SECRET_NAME = "auth0-preflight-token-cache-v2"

def info(*args, **kwargs):
  # Printing into to stderr, so that stdout is only the token
  print(*args, file=sys.stderr, **kwargs)

def require_env(key: str) -> str:
  ret = os.getenv(key)
  if not ret:
    raise AssertionError(f"Environment variable {key} is undefined")
  return ret

class Auth0Domain:
  domain: str
  mgmt_client_id: str
  mgmt_client_secret: str

  def __init__(self, domain: str, mgmt_client_id: str, mgmt_client_secret: str):
    self.domain = domain
    self.mgmt_client_id = mgmt_client_id
    self.mgmt_client_secret = mgmt_client_secret


def is_legacy_infra(infra_outputs: dict) -> bool:
  return "appToClientId" in infra_outputs["auth0"]["cantonNetwork"]

class Auth0AppAndAudience:
  client_id: str
  pulumi_stack: str
  domain: Auth0Domain
  audience: str
  pulumi_infra_outputs: None | dict = None

  def get_pulumi_infra_outputs(self) -> dict:
    if self.pulumi_infra_outputs is None:
      info("Getting outputs from Pulumi infra stack")
      cncluster_pulumi_res = subprocess.run(["cncluster", "pulumi", "infra", "stack", "output", "--json"], stdout=subprocess.PIPE)
      cncluster_pulumi_res.check_returncode()
      infra_outputs_str = cncluster_pulumi_res.stdout.decode('ascii').split("\n", 1)[1]
      self.pulumi_infra_outputs = json.loads(infra_outputs_str)
    return self.pulumi_infra_outputs

  def init_pulumi_stack(self, args: argparse.Namespace) -> str:
    if args.namespace == "sv":
      self.pulumi_stack = "svRunbook"
    elif args.namespace == "validator":
      self.pulumi_stack = "validatorRunbook"
    else:
      self.pulumi_stack = "cantonNetwork"

    info(f"Pulumi stack: {self.pulumi_stack}")


  def init_client_id(self, args: argparse.Namespace):
    if args.client_id:
      self.client_id = args.client_id
    else:
      infra_outputs = self.get_pulumi_infra_outputs()

      if is_legacy_infra(infra_outputs):
        # Legacy infra data structures
        # TODO(#2873): remove this once infra on all clusters has been migrated
        if args.namespace == "sv":
          if args.app not in ["sv", "validator"]:
            raise ValueError("Only sv and validator apps are supported in sv namespace")
          self.client_id = infra_outputs["auth0"]["svRunbook"]["appToClientId"][args.app]
        elif args.namespace == "validator":
          if args.app != "validator":
            raise ValueError("Only validator app is supported in validator namespace")
          self.client_id = infra_outputs["auth0"]["validatorRunbook"]["appToClientId"][args.app]
        elif args.namespace == "validator1":
          if args.app != "validator":
            raise ValueError("Only validator app is supported in validator1 namespace")
          self.client_id = infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["validator1"]
        elif args.namespace == "splitwell":
          if args.app == "validator":
            self.client_id = infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["splitwell_validator"]
          elif args.app == "splitwell":
            self.client_id = infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["splitwell"]
          else:
            raise ValueError("Only splitwell and validator apps are supported in splitwell namespace")
        elif args.namespace.startswith("sv-"):
          if args.app == "sv":
            self.client_id = infra_outputs["auth0"]["cantonNetwork"]["appToClientId"][args.namespace]
          elif args.app == "validator":
            self.client_id = infra_outputs["auth0"]["cantonNetwork"]["appToClientId"][f"{args.namespace.replace("-","")}_validator"]
          else:
            raise ValueError(f"Only sv and validator apps are supported in {args.namespace} namespace")
        else:
          raise ValueError(f"Unknown namespace: {args.namespace}")
      else:
        if args.app == "validator":
          app = "validator"
        elif args.app == "sv":
          app = "svApp"
        elif args.app == "splitwell":
          app = "splitwell"
        else:
          raise ValueError(f"Unknown app: {args.app}")
        if args.namespace not in infra_outputs["auth0"][self.pulumi_stack]["namespacedConfigs"]:
          raise ValueError(f"Unknown namespace: {args.namespace}")
        if app not in infra_outputs["auth0"][self.pulumi_stack]["namespacedConfigs"][args.namespace]["backendClientIds"]:
          raise ValueError(f"Unknown app: {args.app}")
        self.client_id = infra_outputs["auth0"][self.pulumi_stack]["namespacedConfigs"][args.namespace]["backendClientIds"][app]


    info(f"Client ID: {self.client_id}")

  def init_auth0_domain(self, args: argparse.Namespace):
    domain = args.auth0_domain
    if not domain:
      infra_outputs = self.get_pulumi_infra_outputs()
      domain = infra_outputs["auth0"][self.pulumi_stack]["auth0Domain"]

    if domain == "canton-network-dev.us.auth0.com":
      self.auth0_domain = Auth0Domain(domain, require_env('AUTH0_CN_MANAGEMENT_API_CLIENT_ID'), require_env('AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET'))
    elif domain == "canton-network-sv-test.us.auth0.com":
      self.auth0_domain = Auth0Domain(domain, require_env('AUTH0_SV_MANAGEMENT_API_CLIENT_ID'), require_env('AUTH0_SV_MANAGEMENT_API_CLIENT_SECRET'))
    elif domain == "canton-network-validator-test.us.auth0.com":
      self.auth0_domain = Auth0Domain(domain, require_env('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_ID'), require_env('AUTH0_VALIDATOR_MANAGEMENT_API_CLIENT_SECRET'))
    else:
      raise ValueError(f"Unknown auth0 domain: {domain}")

    info(f"auth0 domain: {self.auth0_domain.domain}")

  def get_app(self, args: argparse.Namespace):
    if args.app:
      return args.app
    else:
      infra_outputs = self.get_pulumi_infra_outputs()
      if is_legacy_infra(infra_outputs):
        # Legacy infra data structures
        # TODO(#2873): remove this once infra on all clusters has been migrated
        if self.pulumi_stack == "svRunbook":
          self.namespace = "sv"
          if self.client_id == infra_outputs["auth0"][self.pulumi_stack]["appToClientId"]["sv"]:
            return "sv"
          elif self.client_id == infra_outputs["auth0"][self.pulumi_stack]["appToClientId"]["validator"]:
            return "validator"
          else:
            raise ValueError(f"Unknown client_id: {self.client_id}")
        elif self.pulumi_stack == "validatorRunbook":
          return "validator"
        else:
          if self.client_id == infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["splitwell"]:
            return "splitwell"
          elif self.client_id == infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["splitwell_validator"]:
            return "validator"
          elif self.client_id == infra_outputs["auth0"]["cantonNetwork"]["appToClientId"]["validator1"]:
            return "validator"
          elif self.client_id in infra_outputs["auth0"]["cantonNetwork"]["appToClientId"].values():
            app_in_infra = [x for x in infra_outputs["auth0"]["cantonNetwork"]["appToClientId"].keys() if infra_outputs["auth0"]["cantonNetwork"]["appToClientId"][x] == self.client_id][0]
            if re.match("^sv-[0-9]+", app_in_infra):
              return "sv"
            elif re.match("^sv[0-9]+_validator", app_in_infra):
              return "validator"
            else:
              raise ValueError(f"Unknown client_id: {self.client_id}")
          else:
            raise ValueError(f"Unknown client_id: {self.client_id}")


  def init_audience(self, args: argparse.Namespace):
    if args.audience:
      self.audience = args.audience
    else:
      app = self.get_app(args)
      infra_outputs = self.get_pulumi_infra_outputs()
      if is_legacy_infra(infra_outputs):
        # Legacy infra data structures
        # TODO(#2873): remove this once infra on all clusters has been migrated
        if app in infra_outputs["auth0"][self.pulumi_stack]["appToApiAudience"]:
          self.audience = infra_outputs["auth0"][self.pulumi_stack]["appToApiAudience"][app]
        else:
          if app == "sv":
            self.audience = require_env("OIDC_AUTHORITY_SV_AUDIENCE")
          else:
            self.audience = require_env("OIDC_AUTHORITY_VALIDATOR_AUDIENCE")
      else:
        if app == "sv":
          self.audience = infra_outputs["auth0"][self.pulumi_stack]["namespacedConfigs"][args.namespace]["audiences"]["svAppApi"]
        elif app == "validator":
          self.audience = infra_outputs["auth0"][self.pulumi_stack]["namespacedConfigs"][args.namespace]["audiences"]["validatorApi"]
    info(f"Audience: {self.audience}")

  def __init__(self, args: argparse.Namespace):
    self.init_pulumi_stack(args)
    self.init_client_id(args)
    self.init_auth0_domain(args)
    self.init_audience(args)


class Token:
  access_token: str
  expires_at: datetime.datetime

  def __init__(self, token):
    self.access_token = token['access_token']
    expires_in = token['expires_in']
    self.expires_at = datetime.datetime.now() + datetime.timedelta(seconds=expires_in)

class AppTokens:
  client_id: str
  data: dict[str, Token]

  def __init__(self, client_id: str):
    self.data = {}
    self.client_id = client_id

  def get_access_token(self, app: Auth0AppAndAudience) -> str:
    audience = app.audience
    if (audience not in self.data) or \
      (self.data[audience].expires_at < datetime.datetime.now() + MIN_EXPIRATION_DELTA):

      info("Token not in cache, or expires too soon, fetching new token")
      self.data[audience] = self.fetch_new_auth0_token(app)
    else:
      info("Token in cache and not expiring soon, returning cached token")

    if self.data[audience].expires_at < datetime.datetime.now() + MIN_EXPIRATION_DELTA:
      raise ValueError("Retrieved token has expiration in less than MIN_EXPIRATION_DELTA, check the Auth0 configuration")

    token = self.data[audience]
    return token.access_token

  def fetch_new_auth0_token(self, app: Auth0AppAndAudience) -> Token:
    auth0_domain = app.auth0_domain
    get_mgmt_token = GetToken(auth0_domain.domain, auth0_domain.mgmt_client_id, auth0_domain.mgmt_client_secret)
    mgmt_token = get_mgmt_token.client_credentials(f"https://{auth0_domain.domain}/api/v2/")
    mgmt_api_token = mgmt_token['access_token']
    auth0 = Auth0(auth0_domain.domain, mgmt_api_token)
    client_secret = auth0.clients.get(self.client_id)["client_secret"]

    get_token = GetToken(auth0_domain.domain, self.client_id, client_secret)
    token = get_token.client_credentials(app.audience)
    return Token(token)

class Cache:
  data: dict[str, AppTokens]

  def __init__(self):
    self.data = {}

  def get_access_token(self, app: Auth0AppAndAudience):
    if app.client_id not in self.data:
      self.data[app.client_id] = AppTokens(app.client_id)
    return self.data[app.client_id].get_access_token(app)


def main(args: argparse.Namespace):
  config.load_kube_config()
  k = client.CoreV1Api()
  try:
    k.read_namespaced_secret(CACHE_SECRET_NAME, "default")
    info("Found cache secret, loading cache")
  except ApiException as e:
    if e.status == 404:
      info("Cache secret not found, creating new one")
      k.create_namespaced_secret("default", client.V1Secret(metadata=client.V1ObjectMeta(name=CACHE_SECRET_NAME), data={}))
      cache = Cache()
    else:
      raise e
  try:
    cache = jsonpickle.decode(base64.b64decode(k.read_namespaced_secret(CACHE_SECRET_NAME, "default").data["data"]).decode('ascii'))
  except Exception as e:
    info(f"Error decoding cache ({e}), creating new one")
    cache = Cache()

  app = Auth0AppAndAudience(args)

  access_token = cache.get_access_token(app)
  print(access_token)

  info("Saving cache to k8s secret")
  encoded = jsonpickle.encode(cache)
  k.replace_namespaced_secret(CACHE_SECRET_NAME, "default", client.V1Secret(metadata=client.V1ObjectMeta(name=CACHE_SECRET_NAME), data={"data": base64.b64encode(encoded.encode('ascii')).decode('ascii')}))

def parse_args():
  parser = argparse.ArgumentParser()
  parser.add_argument('--namespace', required=False, help="The namespace for which to fetch the token. Either client_id or namespace & app must be provided")
  parser.add_argument('--app', required=False, help="The app for which to fetch the token. Either client_id or namespace & app must be provided")
  parser.add_argument('--client-id', required=False, help="The explicit auth0 Client ID for which to fetch the token. Either client_id or namespace & app must be provided")
  parser.add_argument('--auth0-domain', required=False, help="The auth0 domain in which the client ID is defined. If not provided, will be extracted from Pulumi infra's output")
  parser.add_argument('--audience', required=False)

  args = parser.parse_args()

  if not (args.namespace and args.app) and not args.client_id:
    raise ValueError("Either client_id or namespace & app must be provided")
  if (args.namespace or args.app) and args.client_id:
    raise ValueError("Either client_id or namespace & app must be provided, but not both")

  return args

if __name__ == "__main__":
  args = parse_args()
  main(args)
