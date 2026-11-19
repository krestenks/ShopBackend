#!/bin/bash
# Switches the hub port of one EC25 modem off and on, then waits until the modem enumerates again.
#
# Usage: ec25-port-cycle <usb device name>      for example 1-1.4.3.2
# Install: sudo install -m 755 scripts/ec25-port-cycle.sh /usr/local/bin/ec25-port-cycle
# The backend runs it as: sudo -n /usr/local/bin/ec25-port-cycle <name>
#
# It accepts only the name of a USB device that is an EC25 (2c7c:0125), so it cannot write anywhere else.

set -u

name="${1:-}"
if ! [[ "$name" =~ ^[0-9]+-[0-9]+(\.[0-9]+)+$ ]]; then
  echo "not a USB device name: $name"; exit 2
fi
dev="/sys/bus/usb/devices/$name"
if [ ! -d "$dev" ] || [ "$(cat "$dev/idVendor" 2>/dev/null)" != "2c7c" ] || [ "$(cat "$dev/idProduct" 2>/dev/null)" != "0125" ]; then
  echo "$name is not an EC25 modem"; exit 3
fi

hub="${name%.*}"          # 1-1.4.3.2 -> 1-1.4.3
port="${name##*.}"        # 1-1.4.3.2 -> 2
disable="/sys/bus/usb/devices/${hub}:1.0/${hub}-port${port}/disable"
if [ ! -w "$disable" ]; then
  echo "hub port control not found: $disable"; exit 4
fi

# The port is switched on again however this script ends, so a signal in the 4 s window cannot leave it off.
trap 'echo 0 > "$disable"' EXIT HUP INT TERM

echo 1 > "$disable" || { echo "could not switch the port off"; exit 5; }
sleep 4
echo 0 > "$disable" || { echo "could not switch the port on"; exit 6; }

for _ in $(seq 1 30); do
  sleep 2
  if [ "$(cat "$dev/idVendor" 2>/dev/null)" = "2c7c" ] && ls "/dev/serial/by-path/" 2>/dev/null | grep -q -- ":${name#*-}:1.0-port0"; then
    sleep 5
    echo "modem $name is back"; exit 0
  fi
done
echo "modem $name did not come back within 60 s"; exit 7
