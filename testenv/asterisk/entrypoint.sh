#!/bin/sh
# Generates the parts of the Asterisk configuration that depend on secrets, then starts Asterisk
# in the foreground. Secrets come from the environment (see testenv/up.sh) and are never stored
# in the image or in the repository.
#
# ROLE=pbx (default) starts the test PBX, ROLE=phones the scripted desk phones 1002-1005.
set -eu

: "${SIP_PASSWORD:?SIP_PASSWORD is required}"
: "${AMI_USER:?AMI_USER is required}"
: "${AMI_SECRET:?AMI_SECRET is required}"

mkdir -p /var/run/asterisk

write_manager_conf() {
  cat > "$1/manager.conf" <<AMI
[general]
enabled = yes
bindaddr = 0.0.0.0
port = 5038

[$AMI_USER]
secret = $AMI_SECRET
read = all
write = all
AMI
}

start_pbx() {
  : "${VM_PIN:?VM_PIN is required}"
  conf=/etc/asterisk

  # 100 phone accounts (1001-1100) sharing one template.
  {
    cat "$conf/pjsip_wizard.template"
    printf 'inbound_auth/password = %s\n\n' "$SIP_PASSWORD"
    i=1001
    while [ "$i" -le 1100 ]; do
      # The mailbox is announced unasked (endpoint) and to a phone that subscribes to it (aor).
      printf '[%s](phone)\ninbound_auth/username = %s\nendpoint/callerid = Ext %s <%s>\nendpoint/mailboxes = %s@default\naor/mailboxes = %s@default\n\n' \
        "$i" "$i" "$i" "$i" "$i" "$i"
      i=$((i + 1))
    done
  } > "$conf/pjsip_wizard.conf"

  write_manager_conf "$conf"

  # Published on an address of the host for a device on the local network (see
  # docker-compose.lan.yml): announce that address to everybody outside the test network.
  : > "$conf/pjsip_lan.conf"
  if [ -n "${LAN_ADDRESS:-}" ]; then
    : "${LOCAL_NET:?LOCAL_NET is required with LAN_ADDRESS}"
    for transport in transport-udp transport-tcp transport-tls; do
      printf '[%s](+)\nlocal_net = %s\nexternal_media_address = %s\nexternal_signaling_address = %s\n\n' \
        "$transport" "$LOCAL_NET" "$LAN_ADDRESS" "$LAN_ADDRESS"
    done > "$conf/pjsip_lan.conf"
  fi

  {
    printf '[general]\nformat = wav\nminsecs = 1\nmaxsilence = 0\n\n[default]\n'
    i=1001
    while [ "$i" -le 1100 ]; do
      printf '%s => %s,Ext %s\n' "$i" "$VM_PIN" "$i"
      i=$((i + 1))
    done
  } > "$conf/voicemail.conf"

  exec asterisk -f -vvv
}

start_phones() {
  : "${PBX_ADDRESS:?PBX_ADDRESS is required}"
  conf=/etc/asterisk-phones
  cp /etc/asterisk/modules.conf /etc/asterisk/logger.conf /etc/asterisk/rtp.conf "$conf/"
  write_manager_conf "$conf"

  {
    cat <<PJSIP
[global]
type = global
user_agent = SipLink-TestPhones

[transport-udp]
type = transport
protocol = udp
bind = 0.0.0.0:5060

; Calls arriving from the PBX, whichever phone they are for.
[pbx-in]
type = endpoint
context = phones
disallow = all
allow = ulaw,alaw
direct_media = no

[pbx-in]
type = identify
endpoint = pbx-in
match = $PBX_ADDRESS

PJSIP
    for phone in 1002 1003 1004 1005; do
      cat <<PJSIP
[auth-$phone]
type = auth
auth_type = userpass
username = $phone
password = $SIP_PASSWORD

[as-$phone]
type = aor
contact = sip:$PBX_ADDRESS

; Calls placed by phone $phone: Dial(PJSIP/<number>@as-$phone)
[as-$phone]
type = endpoint
context = phones
disallow = all
allow = ulaw,alaw
direct_media = no
outbound_auth = auth-$phone
aors = as-$phone
from_user = $phone

[reg-$phone]
type = registration
outbound_auth = auth-$phone
server_uri = sip:$PBX_ADDRESS
client_uri = sip:$phone@$PBX_ADDRESS
contact_user = $phone
retry_interval = 5
expiration = 300

PJSIP
    done
  } > "$conf/pjsip.conf"

  exec asterisk -f -vvv -C "$conf/asterisk.conf"
}

case "${ROLE:-pbx}" in
  pbx) start_pbx ;;
  phones) start_phones ;;
  *) echo "Unknown ROLE: $ROLE" >&2; exit 1 ;;
esac
