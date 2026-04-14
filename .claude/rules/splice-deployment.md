# Splice Deployment

## Helm Charts

### Location
`cluster/helm/`

### App Charts
- `splice-splitwell-web-ui/`, `splice-scan-web-ui/`, `splice-sv-web-ui/`, `splice-wallet-web-ui/`, `ans-web-ui/`
- Shared library: `splice-util-lib/` (annotations, labels, service accounts)

### Chart Structure
```
chart-name/
├── Chart-template.yaml
├── values-template.yaml        # Defaults (resources, auth config)
├── values.schema.json          # Helm value validation
├── templates/
│   ├── app-web-ui.yaml         # Deployment + Service
│   └── required.yaml           # Required config validation
└── tests/
    └── app_web_ui_test.yaml    # helm-unittest tests
```

### Key Helm Values
```yaml
resources:
  requests: { cpu: 100m }
  limits: { memory: 1536Mi }
enableReloader: true  # Stakater reloader for ConfigMap hot-reload
spliceInstanceNames:
  networkName, amuletName, nameServiceName, etc.
```

### Testing
```bash
make cluster/helm/test           # Run all Helm tests
helm unittest cluster/helm/CHART # Specific chart
helm unittest -d cluster/helm/CHART  # Debug: render YAML to .debug/
```

## Pulumi IaC

### Location
`cluster/pulumi/`

### Stack Structure
- `common/` — shared utilities (`@lfdecentralizedtrust/splice-pulumi-common`)
- `deployment/` — deployment stack definitions
- `cluster/` — K8s cluster provisioning
- `gcp/` — GCP resources
- `canton-network/` — Canton domain/participant setup
- `splitwell/`, `sv/`, `validator1/` — per-component stacks
- `observability/` — monitoring/metrics

### Conventions
- Stack naming: `organization/{project}/{stackName}.{CLUSTER_BASENAME}`
- Secrets: GCP KMS encryption
- Parallelism: 128 parallel operations
- Policy enforcement via policy packs
- Tests: Jest (`make cluster/pulumi/unit-test`)

### State Checks
```bash
make cluster/pulumi/update-expected  # Update expected deployment state files
```
Compare diffs of `expected` files to confirm changes are intentional.

## Docker Images

### Registry
`ghcr.io/digital-asset/decentralized-canton-sync/docker`

### Frontend Images (Multi-Stage)
1. Copy built app files from `splice-app` image
2. Base: `nginxinc/nginx-unprivileged:1.29.0` (SHA-pinned)
3. Runtime config via `envsubst` → `config.js` → `window.splice_config`

### Nginx Configuration
```nginx
listen 8080
root /usr/share/nginx/html
add_header Cache-Control "no-store"        # Default: no caching
location /assets { expires 1y; }           # Long-term cache for versioned assets
location / { try_files $uri /index.html; } # SPA routing fallback
gzip on; gzip_types text/html text/css application/javascript;
```

### Base Image Policy
All base images pinned by SHA256 digest of multi-arch manifest. Update Dockerfiles with new version + SHA.

### Build
```bash
make cluster/images/{image}/docker-build
make cluster/images/{image}/docker-push
```

## Cluster Deployment

### Network Topology
```
K8s Cluster
├── SV nodes (1-4): SV app + Canton participant + sequencer + mediator + CometBFT
├── Validator nodes: Validator app + Canton participant + wallet
├── Scan: Scan app (indexing service)
├── Frontend: Wallet UI, Scan UI, SV UI, ANS UI, Splitwell UI
├── PostgreSQL: Per-app databases
└── Observability: Prometheus, Grafana
```

### Service URLs (Helm values)
- Splitwell API: `https://splitwell.{cluster}/api/splitwell`
- JSON API: `https://{host}/api/json-api/`
- Scan API: `https://scan.sv-2.{cluster}/api/scan`
- Wallet UI: `https://wallet.{cluster}`

### Secrets Management
- Auth credentials in K8s Secrets (e.g., `splice-app-splitwell-ui-auth`)
- Auth0 management API credentials in `.envrc.private`
- Pulumi stack secrets via GCP KMS

## Cluster Testing

### Cluster Test Types
- `/cluster_test` — basic scratch cluster deploy + test suite
- `/hdm_test` — hard migration workflow test
- `/lsu_test` — logical synchronizer upgrade test

Request via PR comment. Self-approve if DA employee; otherwise contact maintainer.

## Configuration Resolution

### Versioned Config
- `config.resolved.yaml` generated from each cluster's `config.yaml`
- Updated: `make cluster/deployment/update-resolved-config -j`
- CI enforces sync between resolved and unresolved configs

## Canton Instance Management

### Start/Stop
```bash
./start-canton.sh       # Wallclock Canton
./start-canton.sh -s    # Simtime Canton
./start-canton.sh -m    # Minimal (frontend tests)
./start-canton.sh -we   # Wallclock with BFT sequencer
./stop-canton.sh        # Stop Canton
```

### Using Local Canton Build
```bash
start-canton.sh -c <canton-repo>/enterprise/app/target/release/canton-enterprise-<VERSION>/bin/canton
```

### Tmux
- 3 windows: wallclock Canton, simtime Canton, toxiproxy
- Switch: `Ctrl-b w`
- Detach: `Ctrl-b d`

## Bundle Release

`sbt bundle` produces `splice-node.tar.gz` containing:
- App assembly JAR
- All DARs (dynamic + committed)
- Frontend builds
- Sphinx HTML documentation
- Docker Compose configurations
- Grafana dashboards
- Test resources
