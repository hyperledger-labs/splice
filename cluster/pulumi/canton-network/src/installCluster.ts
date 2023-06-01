import * as pulumi from '@pulumi/pulumi';

import type { Auth0Client } from './auth0types';
import { installDocs } from './docs';
import { InfrastructureOutputs } from './infra';
import { installClusterIngress } from './ingress';
import { installSplitwell } from './splitwell';
import { installSVC, installSvNode } from './sv';
import { infraStack } from './utils';
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

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  const svc = await installSVC(auth0Client);
  const validator = await installValidator(auth0Client, svc, 'validator1');
  const splitwell = await installSplitwell(auth0Client, svc);

  await installSvNode(
    auth0Client,
    svc,
    'sv-1',
    'Canton-Foundation-1',
    'auth0|64529b128448ded6aa68048f'
  );
  await installSvNode(
    auth0Client,
    svc,
    'sv-2',
    'Canton-Foundation-2',
    'auth0|64529b6852dd694167351045',
    SV2_KEY
  );
  await installSvNode(
    auth0Client,
    svc,
    'sv-3',
    'Canton-Foundation-3',
    'auth0|64529bb10c1aee4f2c819218',
    SV3_KEY
  );
  await installSvNode(
    auth0Client,
    svc,
    'sv-4',
    'Canton-Foundation-4',
    'auth0|64529bc58d30358eacae5611',
    SV4_KEY
  );

  const docs = installDocs();

  installClusterIngress(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}
