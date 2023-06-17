import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import type { Auth0Client } from 'cn-pulumi-common';
import { infraStack, InfrastructureOutputs } from 'cn-pulumi-common';

import { installDocs } from './docs';
import { installClusterIngress } from './ingress';
import { installSplitwell } from './splitwell';
import { installSvNode, SvOnboarding } from './sv';
import { installValidator } from './validator';

/// Toplevel Chart Installs

const SV2_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOouqxvUir3C9+2apEdOUC40XrbLTdkbBIK78o2m3lOKhRANCAASxFGe02Q4sXZaHsnFXSsFA+BP5J6d0iMUtcpRcKsttUeqiaTKkdCJk/w6AUxKUHI6evzV+qJS3cbfotSmD9+aA',
};

const SV3_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0fnbBQiM7UiSNaV6tjPq5lK2buIx5L5nzUuhYWxBk341nFChcbK9pDEO4O6gdxexb/OQP6RhQkDOTDdTCr77CA==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+8jKTfry5rkitnvy9Dyh5uPVKTzcKu3rrPZyrVW9e/KhRANCAATR+dsFCIztSJI1pXq2M+rmUrZu4jHkvmfNS6FhbEGTfjWcUKFxsr2kMQ7g7qB3F7Fv85A/pGFCQM5MN1MKvvsI',
};

const SV4_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa76d2OWmkpCQ2dTWsWyhofV3tOGdlkhoCnPpY7BbQhCb0s3laR1vp57JYu/d5Cf+332PF2XrgjC0yBWUqM4syQ==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgE5r1MpzeTmvYjtiVLDASw63VA2pfQm4psX7XlUJU8fGhRANCAARrvp3Y5aaSkJDZ1NaxbKGh9Xe04Z2WSGgKc+ljsFtCEJvSzeVpHW+nnsli793kJ/7ffY8XZeuCMLTIFZSozizJ',
};

function joinViaSv1(
  sv1: k8s.helm.v3.Release,
  keys: { publicKey: string; privateKey: string }
): SvOnboarding {
  return {
    type: 'join-with-key',
    sponsorApiUrl: 'http://sv-app.sv-1:5014',
    sponsorRelease: sv1,
    ...keys,
  };
}

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  const sv1 = await installSvNode(
    auth0Client,
    'sv-1',
    'Canton-Foundation-1',
    'auth0|64529b128448ded6aa68048f',
    { type: 'found-collective' },
    true
  );
  await installSvNode(
    auth0Client,
    'sv-2',
    'Canton-Foundation-2',
    'auth0|64529b6852dd694167351045',
    joinViaSv1(sv1, SV2_KEY),
    true
  );
  await installSvNode(
    auth0Client,
    'sv-3',
    'Canton-Foundation-3',
    'auth0|64529bb10c1aee4f2c819218',
    joinViaSv1(sv1, SV3_KEY)
  );
  await installSvNode(
    auth0Client,
    'sv-4',
    'Canton-Foundation-4',
    'auth0|64529bc58d30358eacae5611',
    joinViaSv1(sv1, SV4_KEY)
  );

  const validator = await installValidator(
    auth0Client,
    sv1,
    'validator1',
    'auth0|63e3d75ff4114d87a2c1e4f5'
  );
  const splitwell = await installSplitwell(auth0Client, sv1, 'auth0|63e12e0415ad881ffe914e61');

  const docs = installDocs();

  installClusterIngress(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}
