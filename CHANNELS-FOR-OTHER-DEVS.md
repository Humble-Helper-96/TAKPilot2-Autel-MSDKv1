# TAK channels — two messages to send

**Written in Simplified Technical English (ASD-STE100).**

Two findings from tests on a live TAK Server, 15 and 16 August 2026. Each part below is
complete. Send each part to one person.

The full evidence is in `CHANNELS-FINDINGS.md`.

---

# Part 1 — for Rick (TAKPilot)

**Subject: the channel selection destroys markers. Here is the cause and the correction.**

## The fault

TAKPilot lets the pilot select channels. `TakManager.withChannelDest()` then puts the name of
each channel into every message that goes through `sendCot()`:

    <marti><dest group="APD Main" send="true" /></marti>

**If the pilot cannot SEND to one of those channels, the server refuses the whole message.** The
markers do not go to the team. The pilot gets no error, and the application gets no error.

This is in your source and in ours. We forked it, thus we had the same fault for the life of the
application. A pilot on our fleet lost every marker for one week.

## Why the server refuses

`StreamingEndpointRewriteFilter` in the TAK Server source:

```java
Group destGroup = groupManager.hydrateGroup(new Group(destGroupName, Direction.IN));
if (!currentGroups.contains(destGroup)) {
    throw new ForbiddenException("illegal attempt to set group " + destGroupName + ...);
}
```

1. The test is for **Direction.IN**. The certificate must be able to SEND to the channel.
2. The refusal stops the **whole message**. The channels that passed get nothing.
3. The server tells the client nothing. The socket stays open and the write succeeds.

## The test that shows it

One user with two channels: `UAS_Both` (send and receive) and `ADSB` (receive only).

| Channels in the message | Result |
|---|---|
| `UAS_Both` | the marker arrived |
| `UAS_Both` and `ADSB` | **the marker reached no one** |

One bad channel destroys the message for the good channel.

## Two more faults in the same function

1. **It is not applied evenly.** `sendDronePLI` and `sendCameraPoint` call `sendMessage()`
   directly, thus they ignore the selection. A pilot who selects channels to LIMIT who sees the
   aircraft still sends its position to everyone. The function fails in both directions.
2. **`TakGroupAssigner.java` has no caller.** It adds the certificate to every channel, which
   would make the server test pass. Nothing calls it, in your source or in ours. It is 259 lines
   that never operate.

## The correction

**No TAK client puts `<dest group>` on a message.** We examined four TAKAware debug logs. There
is not one `<marti><dest group>` in them. A client sends plain CoT and the server decides who
gets it.

Use the server API instead:

**Read the channels**

    GET /Marti/api/groups/all?useCache=true&sendLatestSA=true

The parameters are necessary. Without them the server omits the IN records, and each channel
looks receive-only. The server sends one record for each channel in each direction:

- two records (IN and OUT) — the certificate can send and receive
- one record (OUT) — receive only
- `active` — the channel is on now. It governs receive as well as send.
- `bitpos` — the number that identifies the channel

**Set the channels**

    PUT /Marti/api/groups/activebits
    [13, 37]

The body is the list of `bitpos` numbers. **The list is absolute.** The server switches off each
channel that is not in it, and an empty list switches off all of them. Send the complete set
each time.

**Follow the server**

The server sends a `t-x-g-c` CoT when the channels change. Read the channels again when one
arrives. Do not use a timer.

## What you get

The server then applies the channels to **everything** that certificate sends and receives: the
aircraft position, the camera point, the pilot position, the markers and the video address. The
message does not change, thus there is no more traffic.

Delete `withChannelDest` and `setChannels`. We did, and the markers came back at once.

**One warning.** The active channels belong to the CERTIFICATE. If two controllers enroll as
one user, a change on one controller changes both. An aircraft that needs its own scope needs
its own certificate.

---

# Part 2 — for Cory (TAK Aware)

**Subject: the server tells you when the channels change. TAK Aware does not use it.**

## What we saw in your logs

Thank you for the debug logs. They gave us the answer to our own fault.

**TAK Aware reads the channels again after each change on the device. That is correct**, and
our application does the same. In one log there are 14 toggles and 15 reads, thus almost every
read follows a toggle.

**A timer finds a change made somewhere else.** In a second log there are no toggles and two
reads, 51 seconds apart. The second read is what found a change made in TAK Portal. Thus a
remote change can wait up to the length of the timer.

**The server already tells you about that change.** Your log shows this line:

    [StreamParser]: Unhandled tasking event received t-x-g-c

`t-x-g-c` is the group change notification of the TAK Server. This is from the server source,
`DistributedSubscriptionManager`:

```java
private static Document groupChangeMessageSeed = ...   // type='t-x-g-c'
makeGroupChangeMessage()
sendGroupsUpdatedMessage()
```

The server sends it when the channels of a user change. It arrives about a tenth of a second
after the change.

## The suggestion

Read the channels again when a `t-x-g-c` arrives. The timer is then not necessary.

The gain is speed, not traffic. A remote change is seen in about one second in place of up to
the length of the timer. Your read after a toggle is correct and does not change.

⚠ The event is a **notice**, not the state. It carries no channel list. You must read the
channels again to see what they are.

## We did this, and it operates

We put it in our own application on 16 August:

- A change in TAK Portal reaches the controller in **one to two seconds**.
- A change on the controller reaches TAK Portal in the same time.
- The application asks the server for nothing while nothing changes.

Our first plan was a timer, the same as yours. The event is better, and it was in the data the
whole time.

## Two things we learned from your logs, with thanks

1. **The query parameters `useCache=true&sendLatestSA=true` are necessary.** We used the bare
   path and got one record for each channel. Each channel then looked receive-only, and we
   concluded the server could not tell us which channels accept a send. That was wrong. Your
   log showed two records for a two-way channel, which is how you separate them in your menu.
2. **`PUT /Marti/api/groups/activebits`.** We did not know this call. Your log showed it. It is
   now the method our application uses.

Our fault was of our own making, but your logs are what corrected it.
