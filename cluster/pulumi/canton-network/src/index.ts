import * as pulumi from '@pulumi/pulumi';

import { installDocs } from './docs';
import { installClusterIngress } from './ingress';
import { installSplitwell } from './splitwell';
import { installSVC, installSvNode } from './sv';
import { infraStack } from './utils';
import { installValidator } from './validator';

/// Toplevel Chart Installs

// TODO(#4459) Move these keys to k8s secrets
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

function installCluster() {
  const svc = installSVC();

  const validator = installValidator(svc, 'validator1');
  const splitwell = installSplitwell(svc);

  installSvNode(svc, 'sv-1', 'Canton-Foundation-1', 'auth0|64529b128448ded6aa68048f');
  installSvNode(svc, 'sv-2', 'Canton-Foundation-2', 'auth0|64529b6852dd694167351045', SV2_KEY);
  installSvNode(svc, 'sv-3', 'Canton-Foundation-3', 'auth0|64529bb10c1aee4f2c819218', SV3_KEY);
  installSvNode(svc, 'sv-4', 'Canton-Foundation-4', 'auth0|64529bc58d30358eacae5611', SV4_KEY);

  const docs = installDocs();

  installClusterIngress(
    infraStack.getOutput('ingressNs') as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}

installCluster();
