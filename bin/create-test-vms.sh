#!/usr/bin/env bash
#
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Creates a Windows test VM (RDP) and a Linux test VM (VNC with XFCE4 + TigerVNC),
# resets the Windows administrator password, and writes a Guacamole batch-import
# JSON file (test-connections.json) placing both VMs under "ROOT/Test Group" and
# granting access to the initial Guacamole admin user.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Load Terraform outputs if required environment variables are not already set.
if [[ -z "${PROJECT_ID:-}" || -z "${ZONE:-}" || -z "${SUBNET:-}" || -z "${ADMIN_EMAIL:-}" || -z "${GUACAMOLE_URL:-}" ]]; then
  echo "==> Loading environment variables from Terraform outputs..."
  # shellcheck source=bin/read-tf-output.sh
  source "${SCRIPT_DIR}/read-tf-output.sh"
fi

WINDOWS_VM="${WINDOWS_VM:-windows-vm}"
WINDOWS_MACHINE_TYPE="${WINDOWS_MACHINE_TYPE:-e2-medium}"
WINDOWS_IMAGE_FAMILY="${WINDOWS_IMAGE_FAMILY:-windows-2025}"
WINDOWS_IMAGE_PROJECT="${WINDOWS_IMAGE_PROJECT:-windows-cloud}"
WINDOWS_USER="${WINDOWS_USER:-admin}"

LINUX_VM="${LINUX_VM:-linux-vm}"
LINUX_MACHINE_TYPE="${LINUX_MACHINE_TYPE:-e2-medium}"
LINUX_IMAGE_FAMILY="${LINUX_IMAGE_FAMILY:-debian-12}"
LINUX_IMAGE_PROJECT="${LINUX_IMAGE_PROJECT:-debian-cloud}"

CONNECTION_GROUP="${CONNECTION_GROUP:-ROOT/Test Group}"
IMPORT_FILE="${IMPORT_FILE:-${REPO_ROOT}/test-connections.json}"

# Normalize admin email to lowercase to match the IAP authentication extension.
ADMIN_EMAIL_LOWER="$(printf '%s' "${ADMIN_EMAIL}" | tr '[:upper:]' '[:lower:]')"

echo "==> Ensuring VPC firewall rule 'permit-guacd-to-vm-traffic' allows SSH (22), RDP (3389), and VNC (5900-5901)..."
gcloud compute firewall-rules update permit-guacd-to-vm-traffic \
  --project="${PROJECT_ID}" \
  --allow=tcp:22,tcp:3389,tcp:5900,tcp:5901 >/dev/null

# ------------------------------------------------------------------------------
# 1. Create or reuse the Linux VNC test VM (started first so package install runs
#    in parallel while the Windows VM boots and completes sysprep).
# ------------------------------------------------------------------------------
if gcloud compute instances describe "${LINUX_VM}" --project="${PROJECT_ID}" --zone="${ZONE}" >/dev/null 2>&1; then
  echo "==> Linux VM '${LINUX_VM}' already exists in ${ZONE}."
  VNC_PASSWORD="$(gcloud compute instances describe "${LINUX_VM}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --format="value(metadata.items[key='vnc-password'].value)")"
  if [[ -z "${VNC_PASSWORD}" ]]; then
    echo "WARNING: Existing Linux VM '${LINUX_VM}' does not have 'vnc-password' metadata; generating a placeholder." >&2
    VNC_PASSWORD="changeme"
  fi
else
  # Classic VNC (RFB 3.8 DES authentication) uses an 8-character password limit.
  VNC_PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 8 || true)"

  TMP_STARTUP="$(mktemp)"
  trap 'rm -f "${TMP_STARTUP}"' EXIT

  cat > "${TMP_STARTUP}" <<'EOF'
#!/bin/bash
set -euxo pipefail

VNC_PASS="$(curl -sf -H "Metadata-Flavor: Google" http://metadata.google.internal/computeMetadata/v1/instance/attributes/vnc-password)"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y --no-install-recommends \
  xfce4 \
  xfce4-terminal \
  dbus-x11 \
  x11-xserver-utils \
  tigervnc-standalone-server \
  tigervnc-common

if ! id -u guacvnc >/dev/null 2>&1; then
  useradd -m -s /bin/bash guacvnc
fi

install -d -m 700 -o guacvnc -g guacvnc /home/guacvnc/.vnc
printf '%s\n' "${VNC_PASS}" | vncpasswd -f > /home/guacvnc/.vnc/passwd
chown guacvnc:guacvnc /home/guacvnc/.vnc/passwd
chmod 600 /home/guacvnc/.vnc/passwd

cat > /home/guacvnc/.vnc/xstartup <<'XEOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
exec dbus-launch --exit-with-session startxfce4
XEOF
chown guacvnc:guacvnc /home/guacvnc/.vnc/xstartup
chmod 755 /home/guacvnc/.vnc/xstartup

su - guacvnc -c "vncserver -kill :1 >/dev/null 2>&1 || true"
su - guacvnc -c "vncserver :1 -geometry 1280x800 -depth 24 -localhost no"
EOF

  echo "==> Creating Linux VNC VM '${LINUX_VM}' (${LINUX_IMAGE_FAMILY}) in ${ZONE}..."
  gcloud compute instances create "${LINUX_VM}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --machine-type="${LINUX_MACHINE_TYPE}" \
    --subnet="${SUBNET}" \
    --no-address \
    --image-family="${LINUX_IMAGE_FAMILY}" \
    --image-project="${LINUX_IMAGE_PROJECT}" \
    --boot-disk-size=20GB \
    --boot-disk-type=pd-standard \
    --shielded-secure-boot \
    --metadata="vnc-password=${VNC_PASSWORD}" \
    --metadata-from-file="startup-script=${TMP_STARTUP}"
fi

LINUX_IP="$(gcloud compute instances describe "${LINUX_VM}" \
  --project="${PROJECT_ID}" \
  --zone="${ZONE}" \
  --format="get(networkInterfaces[0].networkIP)")"

# ------------------------------------------------------------------------------
# 2. Create or reuse the Windows RDP test VM and reset the Windows password.
# ------------------------------------------------------------------------------
if gcloud compute instances describe "${WINDOWS_VM}" --project="${PROJECT_ID}" --zone="${ZONE}" >/dev/null 2>&1; then
  echo "==> Windows VM '${WINDOWS_VM}' already exists in ${ZONE}."
else
  echo "==> Creating Windows RDP VM '${WINDOWS_VM}' (${WINDOWS_IMAGE_FAMILY}) in ${ZONE}..."
  gcloud compute instances create "${WINDOWS_VM}" \
    --project="${PROJECT_ID}" \
    --zone="${ZONE}" \
    --machine-type="${WINDOWS_MACHINE_TYPE}" \
    --subnet="${SUBNET}" \
    --no-address \
    --image-family="${WINDOWS_IMAGE_FAMILY}" \
    --image-project="${WINDOWS_IMAGE_PROJECT}" \
    --boot-disk-size=50GB \
    --boot-disk-type=pd-standard \
    --shielded-secure-boot
fi

WINDOWS_IP="$(gcloud compute instances describe "${WINDOWS_VM}" \
  --project="${PROJECT_ID}" \
  --zone="${ZONE}" \
  --format="get(networkInterfaces[0].networkIP)")"

echo "==> Resetting Windows password for user '${WINDOWS_USER}' on '${WINDOWS_VM}' (waiting for Windows guest agent if newly created)..."
WINDOWS_PASSWORD=""
for attempt in $(seq 1 20); do
  if WINDOWS_PASSWORD="$(gcloud compute reset-windows-password "${WINDOWS_VM}" \
    --user="${WINDOWS_USER}" \
    --zone="${ZONE}" \
    --project="${PROJECT_ID}" \
    --quiet \
    --format="value(password)" 2>/dev/null)" && [[ -n "${WINDOWS_PASSWORD}" ]]; then
    break
  fi
  echo "    Windows guest agent not ready yet (attempt ${attempt}/20); retrying in 15s..."
  sleep 15
done

if [[ -z "${WINDOWS_PASSWORD}" ]]; then
  echo "ERROR: Failed to reset Windows password on '${WINDOWS_VM}' after 20 attempts." >&2
  exit 1
fi

# ------------------------------------------------------------------------------
# 3. Generate the Guacamole batch-import JSON file.
#    Reference: https://guacamole.apache.org/doc/gug/batch-import.html
# ------------------------------------------------------------------------------
echo "==> Generating Guacamole batch-import JSON file at: ${IMPORT_FILE}"
jq -n \
  --arg group "${CONNECTION_GROUP}" \
  --arg admin_user "${ADMIN_EMAIL_LOWER}" \
  --arg win_name "${WINDOWS_VM} (RDP)" \
  --arg win_ip "${WINDOWS_IP}" \
  --arg win_user "${WINDOWS_USER}" \
  --arg win_pass "${WINDOWS_PASSWORD}" \
  --arg lin_name "${LINUX_VM} (VNC)" \
  --arg lin_ip "${LINUX_IP}" \
  --arg vnc_pass "${VNC_PASSWORD}" \
  '[
    {
      name: $win_name,
      protocol: "rdp",
      group: $group,
      users: [$admin_user],
      parameters: {
        hostname: $win_ip,
        port: "3389",
        username: $win_user,
        password: $win_pass,
        security: "nla",
        "ignore-cert": "true",
        "resize-method": "display-update"
      }
    },
    {
      name: $lin_name,
      protocol: "vnc",
      group: $group,
      users: [$admin_user],
      parameters: {
        hostname: $lin_ip,
        port: "5901",
        password: $vnc_pass,
        "color-depth": "24"
      }
    }
  ]' > "${IMPORT_FILE}"

chmod 600 "${IMPORT_FILE}"

cat <<EOF

================================================================================
Test VMs & Guacamole Import File Ready
================================================================================
  Windows RDP VM : ${WINDOWS_VM} (${WINDOWS_IP}:3389, user: ${WINDOWS_USER})
  Linux VNC VM   : ${LINUX_VM} (${LINUX_IP}:5901, XFCE4 + TigerVNC)
  Target Group   : ${CONNECTION_GROUP}
  Admin User     : ${ADMIN_EMAIL_LOWER}
  Import File    : ${IMPORT_FILE}

Next Steps to Import Connections in Guacamole:
  1. Open ${GUACAMOLE_URL} in your browser and sign in as ${ADMIN_EMAIL_LOWER}.
  2. Click your username in the top-right corner and select "Settings".
  3. Open the "Connections" tab:
     - Note: Guacamole requires the parent connection group to exist before
       importing into "${CONNECTION_GROUP}". If "Test Group" is not already
       listed, click "New Group", enter Name: "Test Group" (Location: ROOT),
       and click "Save".
  4. On the "Connections" tab, click "Import" (top-right action button).
  5. Select or drag-and-drop "${IMPORT_FILE}" (if running in Cloud Shell, use
     "Download File" from the Cloud Shell menu to download test-connections.json
     to your local machine first) and click "Import Connections".
  6. Return to "Home" from the top-right menu, expand "Test Group", and click
     "${WINDOWS_VM} (RDP)" or "${LINUX_VM} (VNC)" to connect!
     (Note: "${LINUX_VM}" takes ~60 seconds on first boot to finish installing
     XFCE4 and starting the TigerVNC server on port 5901.)
================================================================================
EOF
