import * as gcp from '@pulumi/gcp';
import { Key } from '@pulumi/gcp/serviceaccount/key';

import { gcpDnsProject, clusterBasename } from './config';

function configureDNSKey(): Key {
  const serviceAccountName = `projects/${gcpDnsProject}/serviceAccounts/dns01-solver@${gcpDnsProject}.iam.gserviceaccount.com`;

  // Note, creating a new key can fail with a precondition error on an attempt
  // to create keys beyond the tenth.
  const key = new gcp.serviceaccount.Key(`dns01-${clusterBasename}`, {
    serviceAccountId: serviceAccountName,
    publicKeyType: 'TYPE_X509_PEM_FILE',
  });

  return key;
}

export const dnsServiceAccountKey: Key = configureDNSKey();
