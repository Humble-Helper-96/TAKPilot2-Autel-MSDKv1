# TAK channels — what was wrong, and how to build it correctly

**Written in Simplified Technical English (ASD-STE100).**

**Branch `channels-research`, from tag v1.6.0. Tests of 15 and 16 August 2026.**

The method in this document is **tested on hardware, against a live server, in both
directions.** Section 9 says what is still not tested.

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

| Channels named in the message | Result |
|---|---|
| `UAS_Both` (send and receive) | the marker arrived |
| `UAS_Both` and `ADSB` (receive only) | **the marker reached no one** |

The second row is the important one. One bad channel destroyed the message for the good
channel. This is the cause of the fault of 15 August: the pilot had five channels selected, and
`ADSB` alone destroyed every marker.

## 2. This is not how a TAK client does it

TAKAware is the reference. Its logs show two facts:

1. **It puts no `<marti><dest group>` on its messages. Not one, in four logs.** It sends plain
   CoT. The server decides who gets it.
2. **It changes channels with an API call**, not with a message attribute.

**The application did not copy a TAK client. It invented a different method.** The source we
forked has the same invention, thus we did not write the fault, but we did keep it.

## 3. The method that works

### Read the channels

    GET /Marti/api/groups/all?useCache=true&sendLatestSA=true

**The parameters are necessary.** The same certificate, at the same minute, gives different
answers:

| Request | Result |
|---|---|
| `/Marti/api/groups/all` | 2 records. `UAS_Both` shows **OUT** only. |
| `…?useCache=true&sendLatestSA=true` | 3 records. `UAS_Both` shows **IN** and **OUT**. |

The server sends one record for each channel **in each direction**:

- **Two records (IN and OUT)** — the certificate can send and receive.
- **One record (OUT)** — the certificate can receive only. It destroys a message that names it.
- **`active`** — 1 when the channel is on now, 0 when it is off. It governs RECEIVE as well as
  send.
- **`bitpos`** — the number that identifies the channel. The write call needs it.

### Set the channels

    PUT /Marti/api/groups/activebits
    [13, 37]

The body is a list of `bitpos` numbers. **Tested on hardware 16 August: the server answers HTTP
200 and TAK Portal shows the change at once.**

⚠ **THE LIST IS ABSOLUTE.** The server activates the channels in the list and deactivates every
other one. An empty list switches them all off. Send the complete set each time, never a change.

Three related calls exist, and none of them is tested:

- `PUT /groups/active` takes `Group[]` objects in place of numbers.
- `PUT /groups/activeForce` is for an administrator. It pushes to each client of a user.
- `GET/POST /groups/update/{username}` tells the clients that the channels changed.

### Follow the server

**The server pushes a `t-x-g-c` CoT when the channels change.** It arrives about a tenth of a
second after the change, from this controller or from an administrator in TAK Portal.

⚠ **The event is a NOTICE, not the state.** It carries no channel list. Read the channels again
when it arrives.

Do not use a timer. The event is faster, and it asks the server nothing while nothing changes.
TAKAware logs the same event as "Unhandled tasking event received" and polls instead.

**Tested on hardware 16 August: a change in TAK Portal reached the controller in one to two
seconds, and a change on the controller reached TAK Portal in the same time.**

## 4. What this gives you

The server applies the active channels to **everything** for that certificate. This includes
the aircraft position, the camera point, the pilot position, the markers and the video address.

The operator asked for this on 16 August. An aircraft on a sensitive mission must not send to a
channel that must not see it.

**Enforcement is at the server, and it is immediate.** The application does not have to know.
An administrator can take an aircraft off a channel while it flies, and the change is effective
at once. What the controller shows is a mirror, not the control.

**Tested 16 August:** with all channels off, the pilot marker stopped and the deleted markers
did not come back. One channel on from TAK Portal, and the marker returned at once — while the
check box on the controller still showed the old state.

It also costs nothing for each message. The message does not change. Thus there is no more
traffic.

**Data stops at the server.** When the channels became inactive, the ADS-B messages stopped:
191 messages in one log, 0 in the next. The link carries less.

## 5. ⚠ The active channels belong to the CERTIFICATE, not to the aircraft

**The operator confirmed this on 16 August.** If two controllers enroll with the same user, and
one controller changes its channels, **the channels change for both**.

If you want one aircraft on one channel, that aircraft needs its own certificate. Give each
controller its own user before you use this function for security.

## 6. What to build

1. Read the channels with the two parameters, when the screen opens and when TAK connects.
2. Listen for `t-x-g-c`, and read the channels again each time one arrives.
3. To change the channels, `PUT` the complete list of `bitpos` numbers. Read them back.
4. **Send no `<dest group>` on any message.** Delete that code if it comes back.
5. Show each channel, its direction and its active state. A pilot on a sensitive mission must
   see the scope, not trust it.

**Let the pilot switch every channel, including a receive-only one.** The check box is the
`active` flag, and `active` governs receive as well as send. A first version disabled the box on
a receive-only channel. That confused "cannot publish to it" with "cannot use it". It also left
`ADSB` switchable off from TAK Portal, with no way to switch it on from the controller.

**Read the channels when TAK connects, and not only when the screen opens.** The read needs a
connection. A screen opened before TAK is up shows an empty list, and there is no button to
try again.

## 7. What is in the fork we came from

The source we forked has the same fault. It also has `TakGroupAssigner.java`. That file adds the
certificate to every channel on the server, which makes the server test in section 1 pass.
**Nothing calls this file** — not in the fork, and not in our four applications. It is 259 lines
of code that never operates.

Two decisions are open:

1. Delete `TakGroupAssigner.java` from all four applications, or call it.
2. If you call it, know what it does. It gives the certificate send permission on every channel
   of the server. That is wrong for a public-safety server.

## 8. This branch

`channels-research`, from tag v1.6.0. **Not for a release and not for a callout.**

- The channels come from the server and go back to it, with `t-x-g-c` and the connect hook.
- It logs each CoT that goes out, with the passwords removed.
- No `<dest group>` can be sent. Two paths that could still have put it back are closed:
  `sendCot` no longer calls `withChannelDest`, and `TakAutoConnect` no longer re-applies a
  channel list saved by an older build.
- ⚠ `RESEARCH_NO_DROP_LIMITS` is `true`. **The marker height limit and the look-angle limit are
  off.** A marker from the ground has no correct position, and it still goes to the team.

**Set `RESEARCH_NO_DROP_LIMITS` to false before any of this goes to a release.**

## 9. ⚠ A server that does not have channels

Cory Foy, the developer of TAK Aware, gave this warning on 16 August 2026:

> Channels is actually a deeper challenge — admins can turn channels changes off in two
> different ways. And sending a channel change to a non-channel enabled server can actually
> wreak havoc server side (we spent days debugging that with Texas).

**Do not write to a server that does not have channels.** Two rules come from this:

1. Write only when the server gave you a channel. Our screens build a row for each channel the
   server returns, and only a row can start a write. A server with no channels thus gets no
   write. `pushActiveChannels` refuses an empty list as well, so this stays true if another
   caller is added later.
2. A refused write is not a fault to hide. An administrator can stop a client changing its own
   channels. Show the pilot that the server refused, and read the channels again.

**Not known: how to identify such a server before you write to it.** An empty channel list is
the only signal we have, and it is a guess. Ask Cory what the two methods are, and what a
client can read to know.

## 10. What is still not tested

- `PUT /groups/active` and `PUT /groups/activeForce`.
- More than one aircraft on one certificate. Section 5 comes from the operator, not from a test
  here.
- Markers were the only message type tested against a channel. The alert path uses the same
  code, but no part of this application sends an alert.
- The behaviour when the server refuses a `PUT`. Only HTTP 200 was seen.

## 11. Corrections — do not repeat these

This document had five wrong statements before the tests corrected them. Each one came from the
same error: **an absence in our own data was reported as a fact about the server.**

1. *"The API cannot tell us which channels accept a send."* Wrong. We used the URL without the
   parameters and did not see the IN records.
2. *"`FedLaw_InterOp` is receive-only."* Wrong. That came from a truncated read of a log.
3. *"The channel control on the device is a display filter."* Wrong. It calls
   `PUT /groups/activebits`. The first search looked for `POST` and `PUT` in the log, but the
   log says "Sending data to".
4. *"Send one message for each channel."* Not necessary. It was a way to live with bad channel
   names, and the correct method has none.
5. *"Poll the server each few minutes."* Not necessary. The server pushes `t-x-g-c`.
