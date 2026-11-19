#!/bin/sh
# The PBX of the NAT lab: the image of the test PBX (testenv/asterisk) with a configuration of
# its own, written here at every start. Secrets come from the environment (see up.sh).
#
#   1001-1010  accounts with the usual NAT handling of a PBX (rport, contact rewriting, symmetric RTP)
#   3001-3010  accounts without it, as a PBX that trusts what the phone writes
#   600        answers and echoes        601  answers and sends a tone        602  answers, silent
#
# The PBX sits behind the NAT box and announces the box's client-side address (LAB_ADDRESS)
# to everybody: that is where the clients reach it.
set -eu

: "${SIP_PASSWORD:?SIP_PASSWORD is required}"
: "${AMI_USER:?AMI_USER is required}"
: "${AMI_SECRET:?AMI_SECRET is required}"
: "${LAB_ADDRESS:?LAB_ADDRESS is required}"

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
external_media_address = $LAB_ADDRESS

[transport-tcp]
type = transport
protocol = tcp
bind = 0.0.0.0:5060
local_net = 127.0.0.0/8
external_signaling_address = $LAB_ADDRESS
external_media_address = $LAB_ADDRESS
EOF

{
  cat <<EOF
[nat](!)
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
aor/max_contacts = 1
aor/remove_existing = yes
aor/qualify_frequency = 0
inbound_auth/password = $SIP_PASSWORD

EOF
  for first in 1000 3000; do
    template=nat
    [ "$first" = 3000 ] && template=plain
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
exten => _[13]0XX,1,Dial(PJSIP/${EXTEN},25)
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

cat > "$conf/rtp.conf" <<'EOF'
[general]
rtpstart = 22000
rtpend = 22099
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
