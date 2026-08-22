# Webhook Templates

lldp-a can POST the result of every completed capture session to a webhook URL. This is
configured from **Settings → Webhook**.

## Enabling webhooks

1. Open Settings and turn on **Send every session to a webhook**.
2. Enter the **Webhook URL** you want to POST to.
3. Optionally set a **Device name** — useful when several phones/tablets send to the same
   webhook, so you can tell which device a given session came from.
4. Optionally set an **auth header name/value** for endpoints that require authentication.
5. Use **Send test webhook** to confirm everything is wired up before relying on it in the field.

By default, no custom template is needed — sessions are sent in a format ready for a
**Discord channel webhook**. Turn on **Use custom JSON template** to POST to any other
JSON-accepting endpoint with a shape you control.

## Default (Discord) behavior

With **Use custom JSON template** off, every finalized session is sent as:

```json
{"content": "<markdown summary of the session>"}
```

If a **Device name** is set, it's added as a top-level `"username"` key, which Discord uses to
override the webhook's displayed sender name:

```json
{"content": "...", "username": "<device name>"}
```

## Custom templates

A custom template is a JSON document containing `{{placeholder}}` tokens. Before sending,
lldp-a substitutes every placeholder with the session's data, then validates that the result is
still well-formed JSON — if it isn't, the send is aborted and the failure reason is shown (and
logged to the diagnostic log as `Webhook send failed: ...`) instead of making a network call.

Every placeholder value is inserted as a JSON string with proper escaping (quotes, backslashes,
newlines) applied automatically. Your template supplies the surrounding quotes — write
`"{{switch_name}}"`, not `{{switch_name}}` — placeholders always expand to *raw text*, never to
pre-quoted JSON, with two exceptions: `{{summary_json}}` expands to raw text too (a JSON string
representation), so if you want it embedded as a nested JSON *object* rather than an escaped
string, you'll need an endpoint that parses/re-embeds it server-side.

### Placeholder reference

Field placeholders are named after the copy-field IDs and are unaffected by any custom label you
set for that field in Settings — renaming a field's display label never breaks a webhook
template or downstream automation keyed on these names.

| Placeholder | Description |
| --- | --- |
| `{{switch_name}}` | Switch hostname (or "N/A") |
| `{{port_id}}` | Port ID (or "N/A") |
| `{{chassis_id}}` | Chassis ID / model (or "N/A") |
| `{{vlan_id}}` | VLAN ID (or "N/A") |
| `{{management_ip}}` | Management IP (or "N/A") |
| `{{duplex}}` | Duplex (empty if unknown) |
| `{{port_description}}` | Interface description (empty if unknown) |
| `{{system_description}}` | System description (empty if unknown) |
| `{{platform}}` | Platform / hardware model (empty if unknown) |
| `{{software_version}}` | Software version (empty if unknown) |
| `{{capabilities}}` | Capabilities string (empty if unknown) |
| `{{protocols}}` | Protocols seen, e.g. "LLDP, CDP" |
| `{{packet_count}}` | Number of merged packets in the session |
| `{{timestamps}}` | "Start: ..., End: ..." |
| `{{title}}` | The record's display title (custom name, or "switch · port") |
| `{{summary_basic}}` | Full session summary, plain-text format |
| `{{summary_markdown}}` | Full session summary, Markdown format |
| `{{summary_json}}` | Full session summary, JSON format (as a string) |
| `{{device_name}}` | The Device name field from Settings (empty if unset) |

The `summary_*` placeholders honor the current Copy Fields list — only enabled fields appear,
in the order and under the labels configured on the Settings page.

### Example: Discord (equivalent to the default template)

```json
{
  "content": "**{{title}}**\n{{summary_markdown}}",
  "username": "{{device_name}}"
}
```

### Example: generic REST endpoint

```json
{
  "event": "lldp_session_complete",
  "device": "{{device_name}}",
  "switch": "{{switch_name}}",
  "port": "{{port_id}}",
  "vlan": {{vlan_id}},
  "packet_count": {{packet_count}}
}
```

Note `{{vlan_id}}` is used *unquoted* here — since VLAN IDs are numeric text, this only produces
valid JSON when the field is populated with digits. If a session could have a non-numeric or
missing VLAN value ("N/A"), keep it quoted (`"{{vlan_id}}"`) unless your endpoint is prepared to
reject malformed sends — lldp-a's own validation step will already catch and block invalid JSON
that results from unquoted placeholders resolving to non-numeric text.

### Auth header examples

The auth header is a single freeform name/value pair sent with every request, which covers most
authentication schemes without special-casing any of them:

- **Bearer token**: name `Authorization`, value `Bearer <token>`
- **API key header**: name `X-API-Key`, value `<key>`
- **Basic auth**: name `Authorization`, value `Basic <base64(user:pass)>`

## Troubleshooting

- **"Invalid JSON after substitution"**: your template has a syntax error, or an unquoted
  placeholder resolved to text that isn't valid JSON (e.g. `{{vlan_id}}` resolving to `N/A`
  instead of a number). Quote the placeholder or handle the "N/A" case on your endpoint.
- **Send fails silently in the field**: enable **Show log views** in Settings and check the
  diagnostic log for `Webhook send failed: ...` entries, which include the HTTP status code or
  network error.
- **Test succeeds but real sessions don't send**: confirm the webhook toggle is on and the URL is
  saved — the test button uses the same configuration as real sessions, but only the "Send every
  session to a webhook" switch gates automatic sends.
