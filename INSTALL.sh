```bash
#!/bin/bash

# InkBridge v1.0.0 - Linux Setup Script
# This script installs udev rules and configures permissions.

echo "🎨 Starting InkBridge Setup..."

# 1. Check for root/sudo
if [ "$EUID" -ne 0 ]; then 
  echo "Please run as root (use sudo ./INSTALL.sh)"
  exit
fi

# 2. Install udev rules
echo "📦 Installing udev rules..."
cp assets/99-inkbridge.rules /etc/udev/rules.d/
udevadm control --reload-rules
udevadm trigger

# 3. Setup User Groups
# We get the 'real' user if run with sudo
REAL_USER=${SUDO_USER:-$USER}

echo "👥 Adding $REAL_USER to 'input' and 'plugdev' groups..."
groupadd -f input
groupadd -f plugdev
usermod -aG input "$REAL_USER"
usermod -aG plugdev "$REAL_USER"

# 4. Install Desktop Entry & Icon
echo "🖥️  Installing Desktop shortcut..."
mkdir -p /usr/share/icons/hicolor/512x512/apps
cp assets/inkbridge.png /usr/share/icons/hicolor/512x512/apps/
cp assets/inkbridge.desktop /usr/share/applications/

echo "✅ Setup Complete!"
echo "⚠️  IMPORTANT: Please log out and log back in for group changes to take effect."
echo "🚀 You can now find InkBridge in your Application Launcher."
```