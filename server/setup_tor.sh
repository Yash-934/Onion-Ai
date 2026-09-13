#!/usr/bin/env bash
# ==============================================================================
# Onion AI - Tor Hidden Service Automated Setup Script
# Works on Debian, Ubuntu, Kali, and Raspberry Pi OS
# ==============================================================================

set -e

echo "=========================================================="
echo "         🧅 Onion AI - Tor Gateway Setup Script           "
echo "=========================================================="

if [ "$EUID" -ne 0 ]; then
  echo "❌ Error: Please run this script as root or with sudo:"
  echo "   sudo bash server/setup_tor.sh"
  exit 1
fi

echo "[1/4] Installing Tor daemon if not already installed..."
if ! command -v tor &> /dev/null; then
    apt-get update -qq
    apt-get install -y tor
else
    echo "✓ Tor is already installed."
fi

TORRC="/etc/tor/torrc"
HS_DIR="/var/lib/tor/onion-ai"

echo "[2/4] Configuring Tor Hidden Service in $TORRC..."
mkdir -p "$HS_DIR"

# Identify the tor user (debian-tor on Debian/Ubuntu, tor on Arch/Fedora)
TOR_USER="debian-tor"
if ! id "$TOR_USER" &>/dev/null; then
    if id "tor" &>/dev/null; then
        TOR_USER="tor"
    else
        TOR_USER=$(whoami)
    fi
fi

chown -R "$TOR_USER:$TOR_USER" "$HS_DIR"
chmod 700 "$HS_DIR"

if ! grep -q "HiddenServiceDir $HS_DIR" "$TORRC"; then
    cat <<EOF >> "$TORRC"

# === Onion AI Gateway Hidden Service ===
HiddenServiceDir $HS_DIR/
HiddenServicePort 80 127.0.0.1:8443
HiddenServicePort 8443 127.0.0.1:8443
HiddenServiceVersion 3
EOF
    echo "✓ Added HiddenService configuration to $TORRC."
else
    echo "✓ HiddenService configuration already present in $TORRC."
fi

echo "[3/4] Restarting Tor service..."
systemctl restart tor || service tor restart

echo "Waiting for Tor to generate the .onion address..."
for i in {1..15}; do
    if [ -f "$HS_DIR/hostname" ]; then
        break
    fi
    sleep 1
done

if [ -f "$HS_DIR/hostname" ]; then
    ONION_HOST=$(cat "$HS_DIR/hostname")
    echo "=========================================================="
    echo "🎉 SUCCESS! Your Tor Hidden Service is running."
    echo ""
    echo "Your Private .onion Address is:"
    echo "👉  http://$ONION_HOST"
    echo ""
    echo "Enter this URL in the Onion AI Android app (under ⚙️ Config)!"
    echo "=========================================================="
    echo ""
    echo "[4/4] Next, start your FastAPI backend server:"
    echo "   cd server"
    echo "   pip install -r requirements.txt"
    echo "   uvicorn main:app --host 127.0.0.1 --port 8443"
    echo ""
else
    echo "⚠️ Tor service restarted, but hostname was not generated immediately."
    echo "Check Tor status with: sudo systemctl status tor"
fi
