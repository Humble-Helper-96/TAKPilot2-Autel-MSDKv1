# TAK channels — what was wrong, and how to build it correctly

**Written in Simplified Technical English (ASD-STE100).**

**Branch `channels-research`, from tag v1.6.0. Tests of 15 and 16 August 2026.**

Read this before you build channel selection again. It holds the evidence.

## 1. What the application did, and why it failed

The pilot selected channels on the TAK Setup screen. The application then put the name of each
channel into every message:

    <marti><dest group="APD Main" send="true" /></marti>

**If the pilot could not SEND to one of those channels, the server refused the whole message.**
The markers did not go to the team. The pilot got no error. The application got no error.

The code is `StreamingEndpointRewriteFilter` on the server:

```java
Group destGroup = groupManager.hydrateGroup(new Group(destGroupName, Direction.IN));
NavigableSet<Group> currentGroups = (NavigableSet<Group>) cot.getContext(Constants.GROUPS_KEY);
if (!currentGroups.contains(destGroup)) {
    throw new ForbiddenException("illegal attempt to set group " + destGroupName + " for uid" + cot.getUid());
}
```

Three facts come from this code:

1. The test is for **Direction.IN**. The pilot must be able to SEND to the channel.
2. The refusal stops the **whole message**. The channels that passed get nothing.
3. The server tells the application nothing.

**Test results, 16 August 2026:**

| Channels selected | Result |
|---|---|
| `UAS_Both` (send and receive) | the marker arrived |
| `UAS_Both` and `ADSB` (receive only) | **the marker reached no one** |

The second row is the important one. One bad channel destroyed the message for the good
channel. This is the cause of the fault of 15 August: the pilot had five channels selected, and
`ADSB` alone destroyed every marker.

## 2. This is not how a TAK client does it

TAKAware is the reference. Its logs show two facts:

1. **It puts no `<marti><dest group>` on its messages. Not one, in three logs.** It sends plain
   CoT. The server decides who gets it.
2. **It changes channels with an API call**, not with a message attribute.

**The application did not copy a TAK client. It invented a different method.** The original
source we forked has the same invention, thus we did not write the fault, but we did keep it.

## 3. The correct method

### To read the channels

    GET /Marti/api/groups/all?useCache=true&sendLatestSA=true

**The parameters are necessary.** The same certificate, at the same time, gives different
answers:

| Request | Result |
|---|---|
| `/Marti/api/groups/all` | 2 records. `UAS_Both` shows **OUT** only. |
| `…?useCache=true&sendLatestSA=true` | 3 records. `UAS_Both` shows **IN** and **OUT**. |

Each record is one channel in one direction:

- **Two records (IN and OUT)** — the pilot can send and receive. `UAS_Both` sends correctly.
- **One record (OUT)** — the pilot can receive only. `ADSB` destroys a message.
- **`active`** — 1 when the channel is on for this certificate now, 0 when it is off.
- **`bitpos`** — the number that identifies the channel. You need it for the next call.

### To set the channels

    PUT /Marti/api/groups/activebits
    [13, 37]

The body is a list of `bitpos` numbers. The server compares it with each channel:
`Arrays.asList(activebits).contains(group.getBitpos())`.

⚠ **THE LIST IS ABSOLUTE. It replaces all of the active channels.** Send the complete list
each time. An empty list turns off every channel. Do not send a change only.

Three related calls exist:

- `PUT /groups/active` takes `Group[]` objects in place of numbers.
- `PUT /groups/activeForce` is for an administrator. It pushes to each client of a user.
- `GET/POST /groups/update/{username}` tells the clients that the channels changed.

### To keep the list correct

TAKAware asks the server again each few seconds. An administrator can then change the channels
of an aircraft from TAK Portal, and the aircraft obeys without a restart. This was tested: the
channels changed on the device 50 seconds after the change in TAK Portal.

## 4. What this gives you

The server applies the active channels to **everything** for that certificate. This includes
the aircraft position, the camera point, the pilot position, the markers and the video address.

The operator asked for this on 16 August. An aircraft on a sensitive mission must not send to a
channel that must not see it.

It also costs nothing for each message. The message does not change. Thus there is no more
traffic, and one message goes to all the correct channels.

**Data stops at the server.** When the channels became inactive, the ADS-B messages stopped:
191 messages in one log, 0 in the next. The link carries less.

## 5. ⚠ The active channels belong to the CERTIFICATE, not to the aircraft

**The operator confirmed this on 16 August.** If two controllers enroll with the same user, and
one controller changes its channels, **the channels change for both**.

This is important for a sensitive mission. If you want one aircraft on one channel, that
aircraft needs its own certificate. Give each controller its own user before you use this
function for security.

## 6. What to build

1. Read the channels with the two parameters. Ask again each few minutes.
2. Show the pilot each channel, its direction and its active state.
3. To change the channels, `PUT` the complete list of `bitpos` numbers.
4. **Send no `<dest group>` on any message.** Delete that code if it comes back.
5. Show the pilot which channels are active before flight. A pilot on a sensitive mission must
   be able to see the scope, not trust it.

## 7. What is not known

- The `activebits` call was seen in a log, but not its body. The body shape comes from the
  server source, not from a test. **Send one and read the result before you trust it.**
- One certificate was tested with two channels, and one with seven. No test used many
  aircraft.
- No test used `PUT /groups/active` or `activeForce`.
- Markers were the only message tested. The alert path uses the same code, but no part of this
  application sends an alert.

## 8. Corrections — do not repeat these

This document had four wrong statements before the tests corrected them. They are recorded
because each one came from the same error: **an absence of data was reported as a fact.**

1. *"The API cannot tell us which channels accept a send."* Wrong. It can. We used the URL
   without the parameters and did not see the IN records.
2. *"`FedLaw_InterOp` is receive-only."* Wrong. That came from a truncated read of a log. It
   has IN and OUT.
3. *"The channel control on the device is a display filter."* Wrong. It calls
   `PUT /groups/activebits` and changes the server. The first search looked for `POST` and
   `PUT` in the log, but the log says "Sending data to".
4. *"Send one message for each channel."* Not necessary. It was a way to live with bad channel
   names. The correct method has no bad names.
