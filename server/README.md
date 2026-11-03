# Guacamole Server (`server/`)

This directory contains the Kubernetes manifests for deploying **Apache Guacamole Server (`guacd`) 1.6.0** (`guacamole/guacd:1.6.0`) inside the `guacamole` namespace on GKE Autopilot.

---

## Role in the Architecture

`guacamole-server` runs the native `guacd` protocol proxy daemon listening on TCP port `4822`. When an authenticated user selects a remote connection in the `guacamole-client` web application:
1. `guacamole-client` connects to `guacamole-server-service:4822` inside the cluster using the Guacamole protocol.
2. `guacd` loads the appropriate protocol plugin (SSH, RDP, VNC, Telnet, or Kubernetes) and initiates the outbound connection over the private VPC to the target VM (such as `db-mgmt-vm`).
3. `guacd` streams the rendered graphical/terminal updates back to the user's browser through `guacamole-client` over WebSockets/HTTP.

---

## Manifests

| File | Resource | Description |
| :--- | :--- | :--- |
| [`kubernetes-manifests/guacamole-server.deployment.yaml`](kubernetes-manifests/guacamole-server.deployment.yaml) | `Deployment/guacamole-server` | Runs `guacamole/guacd:1.6.0` as non-root user `guacd` (`UID 1000`) with `tcpSocket` readiness and liveness probes on port `4822` and all Linux capabilities dropped. |
| [`kubernetes-manifests/guacamole-server.service.yaml`](kubernetes-manifests/guacamole-server.service.yaml) | `Service/guacamole-server-service` | Cluster-internal `ClusterIP` Service exposing port `4822` (`guacd`) to `guacamole-client`. |
