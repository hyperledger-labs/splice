import * as pulumi from "@pulumi/pulumi";
import * as k8s from "@pulumi/kubernetes";
import * as auth0 from "@pulumi/auth0";


// For now, uses canton-network-sv-test tenant, and assumes env variables AUTH0_DOMAIN, AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET
// With AUTH0_DOMAIN=canton-network-sv-test.us.auth0.com
// and ClientID and Secret copied from: https://manage.auth0.com/dashboard/us/canton-network-sv-test/apis/644fdcbfd1cecaff1c09e136/test

const sv1Auth0ClientId = "bUfFRpl2tEfZBB7wzIo9iRNGTj8wMeIn"
const sv1Auth0Secret = auth0.Client.get("sv1", sv1Auth0ClientId).clientSecret
const validatorAuth0ClientId = "uxeQGIBKueNDmugVs1RlMWEUZhZqyLyr"
const validatorAuth0Secret = auth0.Client.get("validator", validatorAuth0ClientId).clientSecret
const walletUIClientId = "l9MS11POtbvPaVvgzns3Tdj9IDnosLwl"

function participantSecret(
    ns: k8s.core.v1.Namespace,
    appName: string,
    appAuth0ClientId: string,
): k8s.core.v1.Secret {
    const secretName = "cn-app-" + appName + "-ledger-api-auth"
    return new k8s.core.v1.Secret(
        secretName,
        {
            metadata: {
                name: secretName,
                namespace: ns.metadata.name,
            },
            type: "Opaque",
            data: {
                "ledger-api-user": btoa(appAuth0ClientId + "@clients")
            }
        },
        {
            dependsOn: [ns]
        }
    )
}

function appSecret(
    ns: k8s.core.v1.Namespace,
    appName: string,
    appAuth0ClientId: string,
    appAuth0Secret: pulumi.Output<string>,
): k8s.core.v1.Secret {
    const secretName = "cn-app-" + appName + "-ledger-api-auth"
    return new k8s.core.v1.Secret(
        secretName,
        {
            metadata: {
                name: secretName,
                namespace: ns.metadata.name,
            },
            type: "Opaque",
            data: {
                "ledger-api-user": btoa(appAuth0ClientId + "@clients"),
                "url": btoa("https://" + process.env.AUTH0_DOMAIN + "/.well-known/openid-configuration"),
                "client-id": btoa(appAuth0ClientId),
                "client-secret": appAuth0Secret.apply(s => btoa(s)),
            }
        },
        {
            dependsOn: [ns]
        }
    )
}

function uiSecret(
    ns: k8s.core.v1.Namespace,
    appName: string,
    clientId: string,
): k8s.core.v1.Secret {
    const secretName = "cn-app-" + appName + "-auth"
    return new k8s.core.v1.Secret(
        secretName,
        {
            metadata: {
                name: secretName,
                namespace: ns.metadata.name
            },
            type: "Opaque",
            data: {
                "url": btoa("https://" + process.env.AUTH0_DOMAIN),
                "client-id": btoa(clientId)
            }
        },
        {
            dependsOn: [ns]
        }
    )
}

export function configureSecrets(ns: k8s.core.v1.Namespace): k8s.core.v1.Secret[] {
    return [
        appSecret(ns, "sv", sv1Auth0ClientId, sv1Auth0Secret),
        participantSecret(ns, "sv1", sv1Auth0ClientId),

        appSecret(ns, "validator", validatorAuth0ClientId, validatorAuth0Secret),
        participantSecret(ns, "sv1-validator", validatorAuth0ClientId),
        uiSecret(ns, "wallet-ui", walletUIClientId),

        participantSecret(ns, "scan", "dummy"),
        participantSecret(ns, "directory", "dummy"),
        participantSecret(ns, "svc", "dummy")
    ]
}
