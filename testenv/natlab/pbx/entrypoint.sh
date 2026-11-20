#!/bin/sh
# The PBX of the NAT lab: the image of the test PBX (testenv/asterisk) with a configuration of
# its own, written here at every start. Secrets come from the environment (see up.sh).
#
#   1001-1010  accounts with the usual NAT handling of a PBX (rport, contact rewriting, symmetric RTP)
#   3001-3010  accounts without it, as a PBX that trusts what the phone writes
#   4001-4010  as 1001-1010, on port 5062, where the PBX announces its own address instead of the
#              box's: a PBX behind a NAT it does not know of
#   5001-5010  as 3001-3010, with ICE: its candidate is the box's address (ice_host_candidates)
#   6001-6010  as 1001-1010, on port 5064, which announces the box's address for SIP
#              (LAB_SIGNALLING_PORT) and the PBX's own address for the sound: a PBX that knows its
#              external signalling address but not its media address
#   7001-7010  as 1001-1010, with one registration that stands until it expires: a second one
#              (another contact, as over another transport) is refused with 403 (max_contacts = 1,
#              remove_existing = no)
#   600        answers and echoes        601  answers and sends a tone        602  answers, silent
#
# The PBX sits behind the NAT box and announces the box's client-side address (LAB_ADDRESS, SIP
# on LAB_SIP_PORT) to everybody but the accounts on ports 5062 and 5064: that is where the
# clients reach it, so that the sound passes the box as well (media_address: the transport's
# external media address alone left the PBX's own address in the SDP, which no client reaches).
set -eu

: "${SIP_PASSWORD:?SIP_PASSWORD is required}"
: "${AMI_USER:?AMI_USER is required}"
: "${AMI_SECRET:?AMI_SECRET is required}"
: "${LAB_ADDRESS:?LAB_ADDRESS is required}"
: "${PBX_ADDRESS:?PBX_ADDRESS is required}"
LAB_SIP_PORT=${LAB_SIP_PORT:-5060}
LAB_SIGNALLING_PORT=${LAB_SIGNALLING_PORT:-5064}

conf=/etc/asterisk
mkdir -p /var/run/asterisk

cat > "$conf/pjsip.conf" <<EOF
[global]
type = global
user_agent = SipLink-NatLab
endpoint_identifier_order = username,ip

[transport-udp]
type = transport
protocol = udp
bind = 0.0.0.0:5060
local_net = 127.0.0.0/8
external_signaling_address = $LAB_ADDRESS
external_signaling_port = $LAB_SIP_PORT
external_media_address = $LAB_ADDRESS

[transport-tcp]
type = transport
protocol = tcp
bind = 0.0.0.0:5060
local_net = 127.0.0.0/8
external_signaling_address = $LAB_ADDRESS
external_signaling_port = $LAB_SIP_PORT
external_media_address = $LAB_ADDRESS

[transport-inner]
type = transport
protocol = udp
bind = 0.0.0.0:5062

[transport-signalling]
type = transport
protocol = udp
bind = 0.0.0.0:5064
local_net = 127.0.0.0/8
external_signaling_address = $LAB_ADDRESS
external_signaling_port = $LAB_SIGNALLING_PORT
EOF

{
  cat <<EOF
[handling](!)
type = wizard
accepts_auth = yes
accepts_registrations = yes
endpoint/context = internal
endpoint/allow = !all,ulaw,alaw
endpoint/direct_media = no
endpoint/rtp_symmetric = yes
endpoint/force_rport = yes
endpoint/rewrite_contact = yes
aor/max_contacts = 1
aor/remove_existing = yes
aor/qualify_frequency = 0
inbound_auth/password = $SIP_PASSWORD

[nat](!,handling)
endpoint/media_address = $LAB_ADDRESS

[plain](!)
type = wizard
accepts_auth = yes
accepts_registrations = yes
endpoint/context = internal
endpoint/allow = !all,ulaw,alaw
endpoint/direct_media = no
endpoint/rtp_symmetric = no
endpoint/force_rport = no
endpoint/rewrite_contact = no
endpoint/media_address = $LAB_ADDRESS
aor/max_contacts = 1
aor/remove_existing = yes
aor/qualify_frequency = 0
inbound_auth/password = $SIP_PASSWORD

[behind](!,handling)
endpoint/transport = transport-inner

[ice](!,plain)
endpoint/ice_support = yes

[signalling](!,handling)
endpoint/transport = transport-signalling

[single](!,nat)
aor/remove_existing = no

EOF
  for first in 1000 3000 4000 5000 6000 7000; do
    template=nat
    [ "$first" = 3000 ] && template=plain
    [ "$first" = 4000 ] && template=behind
    [ "$first" = 5000 ] && template=ice
    [ "$first" = 6000 ] && template=signalling
    [ "$first" = 7000 ] && template=single
    i=1
    while [ "$i" -le 10 ]; do
      n=$((first + i))
      printf '[%s](%s)\ninbound_auth/username = %s\nendpoint/callerid = Ext %s <%s>\n\n' "$n" "$template" "$n" "$n" "$n"
      i=$((i + 1))
    done
  done
} > "$conf/pjsip_wizard.conf"

cat > "$conf/extensions.conf" <<'EOF'
[general]
static = yes
writeprotect = yes

[internal]
exten => _[134567]0XX,1,Dial(PJSIP/${EXTEN},25)
 same => n,Hangup()

exten => 600,1,Answer()
 same => n,Echo()
 same => n,Hangup()

exten => 601,1,Answer()
 same => n,Milliwatt()
 same => n,Hangup()

exten => 602,1,Answer()
 same => n,Wait(600)
 same => n,Hangup()
EOF

cat > "$conf/rtp.conf" <<EOF
[general]
rtpstart = 22000
rtpend = 22099

[ice_host_candidates]
$PBX_ADDRESS => $LAB_ADDRESS
EOF

cat > "$conf/manager.conf" <<EOF
[general]
enabled = yes
bindaddr = 0.0.0.0
port = 5038

[$AMI_USER]
secret = $AMI_SECRET
read = all
write = all
EOF

exec asterisk -f -vvv
