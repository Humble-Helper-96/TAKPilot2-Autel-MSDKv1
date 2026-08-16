# TAK channel selection — what is wrong, and what to build

**Written in Simplified Technical English (ASD-STE100).**

**Branch `channels-research`, from tag v1.6.0. Test date 16 August 2026.**

This document holds the evidence. Read it before you build channel selection again.

## 1. The fault

The application let a pilot select channels on the TAK Setup screen. It then put the name of
each selected channel into every message:

    <marti><dest group="APD Main" send="true" /></marti>

**If the pilot could not SEND to one of those channels, the server refused the message.** The
markers did not go to the team. The pilot saw no error. The application saw no error.

The aircraft position was not affected, because it does not use this path. Thus the aircraft
looked correct while the markers were lost.

## 2. Why the server refuses

The server does this test on each channel name in the message. The code is in
`StreamingEndpointRewriteFilter`:

```java
Group destGroup = groupManager.hydrateGroup(new Group(destGroupName, Direction.IN));
NavigableSet<Group> currentGroups = (NavigableSet<Group>) cot.getContext(Constants.GROUPS_KEY);
if (!currentGroups.contains(destGroup)) {
    throw new ForbiddenException("illegal attempt to set group " + destGroupName + " for uid" + cot.getUid());
}
```

Three facts come from this code, and all three matter:

1. The test is for **Direction.IN**. The pilot must be able to SEND to the channel. Permission
   to RECEIVE is not sufficient.
2. The refusal stops the **whole message**. The server does not send the message to the
   channels that passed.
3. The server tells the application nothing. The message left the controller correctly.

## 3. The test

A test user had two channels:

| Channel | Permission |
|---|---|
| `UAS_Both` | send and receive |
| `ADSB` | receive only |

| Selected channels | Message sent | Result |
|---|---|---|
| `UAS_Both` | `<dest group="UAS_Both"/>` | **the marker arrived** |
| `UAS_Both` and `ADSB` | `<dest group="UAS_Both"/><dest group="ADSB"/>` | **the marker did not arrive** |

The second test is the important one. The message named one good channel and one bad channel.
**It reached no one.** The good channel did not get the marker.

`ADSB` is the bad channel. `UAS_Both` alone operates, and `UAS_Both` with `ADSB` does not.

This also gives the cause of the fault of 15 August 2026. The pilot had five channels selected:
`ADSB`, `APD Main`, `APD SWAT`, `AK_InterOp` and `FedLaw_InterOp`. `ADSB` alone destroyed every
marker. The other four channels were not the problem.

## 4. The problem you must solve first

**The server does not tell the application which channels the pilot can send to.**

`GET /Marti/api/groups/all` gave this for the test user:

```json
{ "name": "ADSB",     "direction": "OUT", "type": "SYSTEM", "bitpos": 13, "active": true }
{ "name": "UAS_Both", "direction": "OUT", "type": "SYSTEM", "bitpos": 37, "active": true,
  "distinguishedName": "cn=tak_UAS_Both" }
```

The `direction` field is `OUT` for both channels. It is `OUT` for the channel that operates and
`OUT` for the channel that fails. **Do not filter on this field. It does not give the answer.**

One difference is visible: `UAS_Both` has a `distinguishedName` and `ADSB` does not. This can be
the correct test, but the data is two channels from one user. **Get more accounts before you
trust it.**

## 5. What to build

**Send one message for each channel.** Do not put all the channels into one message.

- One message for each selected channel, each with one `<dest group>`, all with the same marker
  uid.
- If a channel is bad, only its own copy is lost. The other channels get the marker.
- The clients use the uid to identify the marker. Two copies of one uid give one marker, not
  two.

This is correct whether or not you can find which channels are good. It changes a total failure
into a partial failure.

Also do these:

- **Show the pilot which channels operate.** When a channel fails, tell the pilot that channel
  by name. The application knows which copy it sent.
- **Send only a name that the server gave you.** Get the list again at each connect. Remove a
  name that is no longer on the server.
- **Keep the outbound CoT log.** It is on this branch. It is what found this fault, and the
  fault is invisible without it.

## 6. What is in the original source

The original source has the same fault. The field, the two functions and the message shape are
the same. We copied the fault; we did not write it.

The original also has `TakGroupAssigner.java`. This file adds the pilot's certificate to every
channel on the server, which makes the test in section 2 pass. **Nothing calls this file** — not
in the original, and not in our four applications. It is 259 lines of code that never operates.

Two decisions are open:

1. Delete `TakGroupAssigner.java` from all four applications, or call it.
2. If you call it, know what it does. It gives the pilot send permission on every channel of the
   server. That can be wrong for a public-safety server.

## 7. This branch

`channels-research`, from tag v1.6.0. It is not for a release and not for a callout.

- It keeps the My Channels control that v1.6.1 removed.
- It logs the full `/Marti/api/groups/all` record, and one line for each channel.
- It logs each CoT that goes to the server, with the passwords removed.
- `RESEARCH_NO_DROP_LIMITS` is `true`. **The marker height limit and the look-angle limit are
  off**, because the test needs markers with the aircraft on the ground. A marker from the
  ground has no correct position, and it still goes to the team.

**Set `RESEARCH_NO_DROP_LIMITS` to false before any of this branch goes to a release.**

## 8. What is not known

- The `distinguishedName` test comes from two channels on one user. It can be a coincidence.
- No test used more than two channels.
- `<dest group>` was tested with markers only. The alert path uses the same code, but nothing in
  this application sends an alert.
