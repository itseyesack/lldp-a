# Using lldp-a

## Connecting the adapter

1. Plug a supported USB-to-Ethernet adapter into your phone/tablet's USB-OTG port.
2. Grant USB permission when prompted (a system dialog, once per adapter unless revoked).
3. Connect the adapter's Ethernet port to the switch port you want to inspect.

The status banner at the top of the app shows the adapter's connection and link state:
disconnected, connecting, connected with link up/down. Tap the info icon (shown once connected)
to see adapter details such as chipset and negotiated speed.

## Running a capture session

Once the link comes up, the app passively listens for LLDP and/or CDP frames — no packets are
sent. Most switches announce every 30-60 seconds, so the **Switchport Discovery** card shows a
spinner until the first frame arrives, then fills in as fields are learned from LLDP and CDP:

- Switch hostname, port ID, chassis ID / model, VLAN, management IP, duplex
- Interface description, system description, platform, software version, capabilities

A session is considered complete once the five core identity fields (switch hostname, port ID,
chassis ID, VLAN, management IP) are all known; at that point it's automatically saved to
History. You can also end a session early with the stop icon on the card, or wait for the app to
finalize it automatically a few seconds after the link drops (e.g. unplugging the adapter).

If the port is a direct host-to-host link rather than a switch port (no LLDP/CDP after 60
seconds, but exactly one other device is seen), the card shows "Connected directly to a host
device" instead of spinning indefinitely.

Use the copy icon on the card to copy the current session's fields to the clipboard, or the
pencil icon to name the record before it's saved to history.

### Peer Devices

The **Peer Devices** card lists every other MAC address seen on the link (sortable by last
seen, first seen, or address). Each entry shows a best-effort vendor label, resolved via
[maclookup.app](https://maclookup.app):

- Broadcast, multicast (IPv4/IPv6, STP, LLDP, Cisco CDP/VTP/PAgP), and randomized/locally
  administered addresses are labeled instantly, with no network lookup.
- Everything else is looked up once per vendor OUI and cached on-device (so the same vendor is
  never queried twice, even across app restarts), keeping requests well within the API's rate
  limits.

## History

Every completed session is kept in **History**, most recent first, up to the limit configured in
Settings → History (100 by default, or unlimited). Tap a record to see full details, or use the
row actions to copy, export (share as JSON), rename, or delete it. **Export All** shares the full
history as one JSON file.

## Settings

Settings is a full page, reached via the gear icon.

### Copy Fields

The list of fields included when you copy a record. Each row has:

- A drag handle (left) to reorder fields — order affects the Basic/Markdown copy text and the
  `summary_*` webhook placeholders (see [WEBHOOK_TEMPLATES.md](WEBHOOK_TEMPLATES.md)).
- A checkbox to include/exclude the field.
- A pencil icon to rename the field's display label (JSON copy/webhook keys stay stable even if
  you rename a label — see the webhook doc for why).

### Copy Format

Choose the format used whenever a record is copied to the clipboard:

- **Basic** — plain `Label: value` lines, no title.
- **Markdown** — a `###` heading with the record title, then `**Label:** value` lines.
- **JSON** — a JSON object keyed by field ID.

### Webhook

Send every completed session to a webhook URL, with an optional device name (useful when
multiple phones share one webhook/channel) and optional auth header. See
[WEBHOOK_TEMPLATES.md](WEBHOOK_TEMPLATES.md) for the full template system, placeholder reference,
and Discord/generic-endpoint examples. Use **Send test webhook** to verify your configuration
without waiting for a real capture session.

### History

Control how many sessions are kept:

- **Keep all history (no limit)** — never drop old sessions automatically.
- Otherwise, set **Max saved sessions** — once reached, the oldest sessions are dropped as new
  ones are saved (default 100).

### Diagnostics

- **Show log views** reveals the live hardware diagnostic log and raw packet log on the main
  screen — useful when troubleshooting adapter bring-up or unexpected field values.
- **Clear All History** permanently deletes all saved records.
