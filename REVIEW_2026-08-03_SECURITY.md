# taklite Security Review — TLS / CoT / cert enrollment — 2026-08-03

Scope: `TakClient` (runtime TLS), `TakCertEnroller` (enrollment + key storage), `CotParser`
(untrusted-XML parsing). This is the security surface the code review flagged as unopened. The
code is inherited from the DJI TAKPilot2 app; findings apply to both.

Ranked most-severe first. Nothing here was changed — TLS/trust changes can break the working
connection to the operator's server, so they are for triage, not silent edits.

---

## 1. Enrollment is MITM-able — credentials + trust bootstrap over an unauthenticated channel  (HIGH)

**Where:** `TakCertEnroller.createEnrollmentSSLContext()` (`:405-442`) and the hostname verifier
duplicated in `httpsGet`/`httpsGetBytes`/`httpsPost` (`:247`, `:276`, `:306`).

**What:** The enrollment `X509TrustManager.checkServerTrusted` only calls `cert.checkValidity()`
(expiry) and a *best-effort* chain check that is swallowed on failure (`:429-436`) — it accepts any
self-signed server certificate. The hostname verifier returns `true` for any session that presented
a certificate (`session.getPeerCertificates()` is non-empty ⇒ accept). Over this connection the app
sends the operator's TAK **username:password** as HTTP Basic (`:47`) and downloads the **truststore**
(`:90-98`).

**Failure scenario:** On a hostile/eavesdropped network during first enrollment, an on-path attacker
presents any self-signed cert. The app accepts it, sends the operator's TAK credentials (harvested),
and the attacker returns an attacker-controlled truststore — which the runtime client then trusts,
extending the compromise to every later session. This is the classic TAK auto-enrollment
trust-on-first-use gap; the code closes none of it (no fingerprint confirmation, no
out-of-band truststore path).

**Mitigations (any one closes most of it):** show the server cert SHA-256 fingerprint to the operator
to confirm out-of-band before credentials are sent; support importing a truststore / data package
OOB and pin to it; at minimum, document that enrollment must run on a trusted network. Full
public-CA validation is not appropriate (TAK servers are typically self-signed), so fingerprint
confirmation is the realistic fix.

## 2. Runtime TLS performs no hostname verification  (MEDIUM)

**Where:** `TakClient.connect()` (`:147-184`).

**What:** The runtime socket is a raw `SSLSocket` from `factory.createSocket(addr, port)` — and it is
handed an `InetAddress` (`:148`, `:178`), which also means no SNI hostname is sent. The code never
sets `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`, so the peer certificate's
identity is **not** checked against `serverAddress`. Unlike enrollment, the cert IS validated against
the *enrolled truststore* (chain + expiry via the real `TrustManager`, `:168-169`), so this is much
less severe.

**Failure scenario:** If the enrolled CA ever signs more than one host (a shared TAK CA), a holder of
any cert from that CA can impersonate the intended server; with a single-server-specific CA the
practical risk is low. **Caveat:** many TAK deployments intentionally skip hostname checks
(IP-addressed / self-signed servers), so this may be deliberate ecosystem parity — confirm against
the target servers before tightening, or it will break connections whose cert CN ≠ address.

**Mitigation:** pass the hostname *String* to `createSocket` (restores SNI) and set endpoint
identification to `"HTTPS"`; or make it a toggle defaulting to on.

## 3. Private key at rest protected only by the well-known "atakatak" password  (MEDIUM, already documented)

**Where:** `TakCertEnroller.DEFAULT_P12_PASSWORD = "atakatak"` (`:36`), `buildClientP12` (`:354-367`).

**What:** The client key + truststore `.p12` files live in app-private `filesDir` under a public,
hard-coded password, so the passphrase adds no secrecy — the only real protection is the app sandbox.
`allowBackup="false"` (manifest) does close the `adb backup` extraction vector, so exposure is
root / physical / a future backup-rule change. A recovered key lets an attacker impersonate the
operator on the TAK network.

**Mitigation:** Android Keystore for the private key (hardware-backed if the controller supports it),
or derive the p12 password from a device-bound secret. Already listed as the Keystore divergence in
PORT-STATUS; recorded here for completeness.

## 4. Unbounded receive buffer  (LOW)

**Where:** `TakClient.run()` `recvBuffer` (`:98-127`).

**What:** Bytes accumulate in a `StringBuilder` until `</event>` appears, with no size cap. A buggy or
compromised (but TLS-authenticated) server that streams without a closing `</event>` grows the buffer
without bound → OOM on this memory-constrained controller (intersects the mid-flight OOM blocker).

**Mitigation:** cap the pending buffer (e.g. a few MB); on overflow, drop the partial and/or drop the
connection. Pure hardening — no protocol/behaviour change for well-formed servers.

## 5. Signed cert not verified against the generated key pair  (LOW, robustness)

**Where:** `TakCertEnroller.enroll()` (`:77-83`).

**What:** The server-returned `signedCert` is bundled with the locally generated private key into the
`.p12` without checking `signedCert.getPublicKey()` matches `keyPair.getPublic()`. Fails closed (a
mismatch just makes later TLS fail), so it is robustness, not a hole — but the failure would surface
far from its cause.

**Mitigation:** one equality check before `buildClientP12`, erroring early with a clear message.

---

## Confirmed SOUND (recorded so they are not re-flagged)

- **CoT parsing is not vulnerable to XXE or entity-expansion DoS.** `CotParser` uses `XmlPullParser`
  (Android KXmlParser) with `FEATURE_PROCESS_DOCDECL` off by default: DOCTYPE is not processed, no
  internal entities are defined, external entities are never fetched, and undefined entity refs throw
  (caught → returns null). Using a pull parser instead of a DOM/SAX `DocumentBuilder` is the right
  call for untrusted server XML. Parsed fields are read into typed values, not evaluated.
- **Runtime refuses plaintext.** `TakClient.connect()` throws if no client cert / truststore is
  present — no silent downgrade to an unencrypted socket (`:152-155`).
- **No credential logging.** Enrollment logs URLs and the username/CN but never the password or the
  Basic-auth header.
- **`allowBackup="false"`** in the manifest blocks `adb backup` extraction of the stored `.p12`s.

## Summary

**High:** 1 (enrollment MITM / credential exposure).
**Medium:** 2 (no runtime hostname check; key-at-rest password) — #3 already known.
**Low:** 2 (unbounded buffer; cert/key match).

Safe to fix now without connectivity risk: **#4** and **#5** (pure hardening). **#1–#3** change
trust/storage behaviour and must be decided against the target TAK servers before touching — #1 is
the one that actually protects the operator's credentials and deserves a decision.

## Disposition — 2026-08-03

- **#4 (unbounded buffer) — ✅ FIXED** (commit 481aae0). `TakClient` caps the pending buffer at
  4 MB and drops the connection past it. Verified on-device: TAK still connects and receives CoT
  normally.
- **#5 (cert/key match) — ✅ FIXED** (commit 481aae0). `TakCertEnroller` verifies the signed cert's
  public key matches the generated key pair before building the `.p12`.
- **#1, #2, #3 — ACCEPTED as standard TAK behaviour** (operator's call, 2026-08-03). Enrollment
  TOFU, the absence of hostname pinning, and the conventional `atakatak` p12 password are how the
  TAK ecosystem operates; kept as notes rather than changed. Revisit #1 (cert-fingerprint
  confirmation) if enrollment ever needs to run on untrusted networks; revisit #3 (Android Keystore)
  before a wider fleet rollout. The mitigations above still stand if the risk posture changes.
