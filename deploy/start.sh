#!/bin/sh
# Start Kinora so other devices on the network can use it (they need the PIN printed below).
cd "$(dirname "$0")"
LAN=1 exec node server.js
