# Plan: price list per therapist, free text

Status: built 2026-11-16 (backend 2a30241, 293 tests pass; app commit made with it). Staging runs it. Tested on the S21 against staging: empty state, edit, save, view, emoji text, counter, cancel, Send. Not tested: an SMS with emojis sent through a real modem, a shop with several therapists (selector), the in-shop login (no Edit button), rotation while the dialog is open. Not deployed to the edge.
Scope: ShopBackend and the ShopManager app. No call handling, no Asterisk, no recovery code, so Rule 1 is not triggered. Deploying restarts the backend, so Rule 2 applies at deploy time.

## Request

Make the price list customizable and per therapist, instead of built from the services in the system. The button shows a dialog with the saved price list and an edit option. The list is text with emojis.

## What it replaces

The "Send price list" button in the message thread built an SMS from the services (name, price, duration) of every employee in the shop. That code is removed from the app. The services in the system are unchanged and still used elsewhere.

## Design

| Topic | Decision |
|---|---|
| Storage | Column `employees.price_list TEXT`, added with the existing additive `ALTER TABLE` list. Null means none saved. Line breaks are stored as `\n`; leading and trailing white space is removed; a blank text clears the list. |
| Limit | 1000 characters, counted in UTF-16 units, so an emoji counts as 2, the same as in an SMS (my choice). A longer text is refused with 400 and the old text stays. |
| Read | `GET /api/mobile/shops/{id}/price-lists` returns `canEdit` and the shop's therapists with their text. Allowed for a manager of the shop's owner and for the shop's own login. Another shop gives 404. |
| Write | `PUT /api/mobile/therapists/{id}/price-list`. Managers only; the therapist must belong to the manager's owner (404 otherwise). |
| Dialog | Opened by the existing button. View mode: the saved text with Send, Edit, Cancel; an empty list shows a hint and Send is disabled. Edit mode: a text field with a counter (characters and about how many SMS), Save, Cancel. A shop with several therapists gets a selector on top. The dialog cannot be dismissed by a tap outside or Back while editing. |
| Sending | The saved text of the selected therapist is sent as it is. The shop's price-list footer from the web admin is still appended, as before. |
| In-shop login | Can view and send, cannot edit (`canEdit` false). |
| Not included | Editing the list from the therapist page. A starting text made from the services. Per-language lists. |

## SMS cost

A text with any emoji is sent as UCS-2: 70 characters in one SMS and 67 per part when split. A list of about 100 characters with 4 emojis is 2 SMS. The counter shows the estimate. UNCERTAIN: whether emojis go out correctly through chan_quectel on the real modems. The source converts UTF-8 to UTF-16 (`utf8_to_ucs2` in `char_conv.c`) and builds UCS-2 parts, so it should work, but no real SMS with an emoji was sent. Check with one SMS to a phone you own before relying on it.
