#!/usr/bin/env bash
# Put MeshAI in the app menu (Activities → MeshAI). Undo: rm ~/.local/share/applications/meshai.desktop
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p ~/.local/share/applications
cat > ~/.local/share/applications/meshai.desktop <<DESK
[Desktop Entry]
Type=Application
Name=MeshAI
Comment=Run one model across this laptop and your phones
Exec=python3 $here/meshai.py
Icon=network-workgroup
Terminal=false
Categories=Development;
DESK
echo "installed: ~/.local/share/applications/meshai.desktop"
