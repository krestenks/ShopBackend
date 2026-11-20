# chan_quectel: MessageSend over AMI — report for upstream

Upstream: [RoEdAl/asterisk-chan-quectel](https://github.com/RoEdAl/asterisk-chan-quectel).
Patch: [chan-quectel-messagesend.patch](chan-quectel-messagesend.patch) (our commit `bd2f96a`).
Affected file: `src/msg_tech.c`, from `d965c58` (2024-06-05, "Send messages via MessageSend
application") onward. Found against Asterisk 22 on 2026-11-20.

## 1. The AMI MessageSend action is refused

`msg_send()` gates on `ast_msg_has_destination()`:

```c
if (ast_msg_has_destination(msg)) {
    ast_log(LOG_ERROR, "Destination number not specified\n");
    return -1;
}
```

`asterisk/message.h` documents that function as reporting whether the message **has a handler
that can find a destination** — not whether the message carries a destination number. For a
message sent through the `MessageSend` AMI action it returns 1, so the send is refused.

Every header shape fails the same way:

| `Destination` | `To` | Result |
|---|---|---|
| `mobile:shop1` | `+45XXXXXXXX` | `Error — Message failed to send.` |
| `mobile:shop1` | `XXXXXXXX` | `Error — Message failed to send.` |
| `mobile:shop1` | `mobile:+45XXXXXXXX` | `Error — Message failed to send.` |
| `mobile:shop1/+45XXXXXXXX` | — | `Error — Message failed to send.` |

Each logs `ERROR src/msg_tech.c: Destination number not specified`.

The dialplan usage in the README is unaffected, because no handler claims the destination there
and the test returns 0. Only the AMI action is reached by the gate.

The destination number is the message's To field, so that is what the patch tests:

```c
const char* const msg_to   = ast_msg_get_to(msg);
const char* const msg_body = ast_msg_get_body(msg);

if (ast_strlen_zero(msg_to)) {
    ast_log(LOG_ERROR, "Destination number not specified\n");
    return -1;
}
```

This matches the existing error text and works for both the dialplan and the AMI paths,
whatever `ast_msg_has_destination()` returns.

## 2. A message sent this way is announced as expired

`parse_report_flag()` in `src/msg_tech.c` defaults to 0 while `cli_sms_send()` in `src/cli.c`
uses `DEF_REPORT = 1`.

`smsdb_outgoing_add()` (`src/at_command.c:571`) tracks every outgoing message for
`validity × 60` seconds — 15 minutes at the default validity — whatever the report flag is. Only
a status report clears the entry (`src/at_response.c:1822`). With no report requested the entry
stays until the TTL lapses, and `handle_expired_reports()` (`src/monitor_thread.c:67`) then
reports the message as `Message expired` with `success: 0`.

Measured: a message enqueued at 10:28:55 was announced expired at 10:43:56, 15 minutes later,
after being delivered to the handset.

Anything consuming the driver's report — the `REPORT` channel variable — therefore sees a
delivered message as a failure. The patch defaults the flag to 1, matching the CLI send.

## 3. Also present, not patched here

`parse_msg_vars()` in the same file reads the `report` message variable through an inverted
test:

```c
} else if (strcasecmp(var_name, "report")) {
    *report = parse_report_flag(var_value);
}
```

`strcasecmp` returns 0 on a match, so this assigns the report flag for every variable **except**
the one named `report`, and a variable actually named `report` is ignored, which means the flag
cannot be set per message as intended.

We have since fixed this in our fork (adding the missing `!`), because our own continuation
variables fed that branch. It is not part of the patch above.
