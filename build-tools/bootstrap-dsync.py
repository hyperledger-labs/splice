#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import logging
import subprocess
import sys

import signal
from typing import NamedTuple

from kubernetes import client, config
import time
import os



def parse_args():
    import argparse
    parser = argparse.ArgumentParser(description="Bootstrap a dedicated sync in the Kubernetes cluster.")
    parser.add_argument("--dsync-name", required=True, help="The name of the dedicated sync to bootstrap.")
    parser.add_argument("--migration-id", required=False, default=1, help="The physical node ID (migration ID) of the dSync to be bootstrapped")
    parser.add_argument("--console-only", action="store_true", help="If set, only the canton console will be started in interactive mode, without running the bootstrap script.")
    return parser.parse_args()

class SequencerMediatorPods(NamedTuple):
    sequencer: client.V1Pod
    mediator: client.V1Pod

def wait_for_pod_to_be_running(pod, namespace):
    api_instance = client.CoreV1Api()
    start_time = time.time()
    while True:
        pod_status = api_instance.read_namespaced_pod_status(pod.metadata.name, namespace)
        if pod_status.status.phase == "Running":
            logging.info(f"Pod '{pod.metadata.name}' in namespace '{namespace}' is running.")
            return
        logging.info(f"Waiting for pod '{pod.metadata.name}' in namespace '{namespace}' to be running...")
        time.sleep(5)
        if time.time() - start_time > 600:
            raise TimeoutError(f"Timed out waiting for pod '{pod.metadata.name}' in namespace '{namespace}' to be running.")

def wait_and_get_sequencer_mediator_pods(node_namespace: str, migration_id: int) -> SequencerMediatorPods:
    api_instance = client.CoreV1Api()
    # For every namespace, find the sequencer and mediator pods with the given migration ID
    sequencers = api_instance.list_namespaced_pod(namespace=node_namespace, label_selector=f"splice-component=sequencer,migration_id={migration_id}")
    mediators = api_instance.list_namespaced_pod(namespace=node_namespace, label_selector=f"splice-component=mediator,migration_id={migration_id}")
    if not sequencers.items:
        raise RuntimeError(f"No sequencer pod found for node '{node_namespace}' with migration ID '{migration_id}'")
    if not mediators.items:
        raise RuntimeError(f"No mediator pod found for node '{node_namespace}' with migration ID '{migration_id}'")
    if len(sequencers.items) > 1:
        raise RuntimeError(f"Multiple sequencer pods found for node '{node_namespace}' with migration ID '{migration_id}'")
    if len(mediators.items) > 1:
        raise RuntimeError(f"Multiple mediator pods found for node '{node_namespace}' with migration ID '{migration_id}'")
    sequencer = sequencers.items[0]
    mediator = mediators.items[0]
    wait_for_pod_to_be_running(sequencer, node_namespace)
    wait_for_pod_to_be_running(mediator, node_namespace)
    return SequencerMediatorPods(sequencer=sequencer, mediator=mediator)

def setup_port_forwarding(pods: list[SequencerMediatorPods]):
    processes = []

    for idx, pod_pair in enumerate(pods):
        local_mediator_admin_api = 11001 + (idx+1) * 100
        pod_mediator_admin_api = 5007
        local_sequencer_public_api = 11002 + (idx+1) * 100
        pod_sequencer_public_api = 5008
        local_sequencer_admin_api = 11003 + (idx+1) * 100
        pod_sequencer_admin_api = 5009

        mediator_process = subprocess.Popen(
            ["kubectl", "port-forward", f"pod/{pod_pair.mediator.metadata.name}", f"{local_mediator_admin_api}:{pod_mediator_admin_api}", "-n", pod_pair.mediator.metadata.namespace],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            preexec_fn=os.setsid
        )
        processes.append(mediator_process)
        logging.info(f"Port forwarding set up for mediator pod '{pod_pair.mediator.metadata.name}' on port {local_mediator_admin_api} (admin API)")

        sequencer_process = subprocess.Popen(
            ["kubectl", "port-forward", f"pod/{pod_pair.sequencer.metadata.name}", f"{local_sequencer_public_api}:{pod_sequencer_public_api}", f"{local_sequencer_admin_api}:{pod_sequencer_admin_api}", "-n", pod_pair.sequencer.metadata.namespace],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            preexec_fn=os.setsid
        )
        processes.append(sequencer_process)
        logging.info(f"Port forwarding set up for sequencer pod '{pod_pair.sequencer.metadata.name}' on ports {local_sequencer_public_api} (public API) and {local_sequencer_admin_api} (admin API)")

    return processes


def start_canton(console_only: bool = False):
    config_file = os.path.join(os.path.dirname(__file__), "local-dsync.conf")
    bootstrap_file = os.path.join(os.path.dirname(__file__), "bootstrap-dsync.sc")
    return subprocess.run(
        ["canton"] + (["run"] if not console_only else []) + ["--no-tty", "-v", "-c", config_file] + ([] if console_only else [bootstrap_file]),
        stdout=sys.stdout,
        stderr=sys.stderr,
        check=False,
    )

def main(args):
    config.load_kube_config()
    api_instance = client.CoreV1Api()

    all_namespaces = api_instance.list_namespace()
    node_namespaces = [ns.metadata.name for ns in all_namespaces.items if ns.metadata.name.startswith(f"{args.dsync_name}-")]
    logging.info(f"Found namespaces for dsync '{args.dsync_name}': {node_namespaces}")
    if len(node_namespaces) != 4:
        raise RuntimeError(f"Expected exactly 4 namespaces for dsync '{args.dsync_name}', but found {len(node_namespaces)}: {node_namespaces}")

    pods = [wait_and_get_sequencer_mediator_pods(node_namespace, args.migration_id) for node_namespace in node_namespaces]
    processes = setup_port_forwarding(pods)

    logging.info("Port forwarding is set up. Starting Canton script.")
    try:
        result = start_canton(console_only=args.console_only)
        logging.info("Canton bootstrap exited with code %s.", result.returncode)
        return result.returncode
    finally:
        for process in processes:
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            except ProcessLookupError:
                pass

if __name__ == "__main__":
  logging.basicConfig(level=logging.INFO)
  args = parse_args()
  main(args)
