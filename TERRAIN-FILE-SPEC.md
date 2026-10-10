# Terrain data: what this application accepts, and how to build it

**Written in Simplified Technical English (ASD-STE100).**

A specification for sourcing or building elevation data for TAKPilot2. **It describes what the
code actually parses, not what MIL-PRF-89020B says.** Where the two differ, this document is
what the application does. The parser is `DtedTile.open`; the importer is `DtedStore.import`.

> ## THE SHORT VERSION
>
> Give the application **DTED Level 2** (`.dt2`) tiles, **one degree square**, **3601 x 3601
> posts**, **big-endian signed-magnitude 16-bit metres above MEAN SEA LEVEL (EGM96)**, with the
> standard **3428-byte header** in front of the data. Put them in a `.zip` and import it on
> Pre-Flight. ⚠ **The vertical datum is the thing that goes wrong** — see §5.

---

## 1. What the application reads

| | |
|---|---|
| Format | DTED (Digital Terrain Elevation Data), binary |
| Accepted extensions | **`.dt0`, `.dt1`, `.dt2` only** — lowercase after the last dot. Anything else in a zip is IGNORED SILENTLY |
| Container for import | a `.zip`, or one bare tile file |
| Levels | any; the application prefers the FINEST available for a point (§4) |

⚠ **THE EXTENSION IS THE ONLY FILTER AT IMPORT, AND THE HEADER IS THE ONLY CHECK AT USE.** A
file named `.dt2` that is not DTED imports without complaint and is then silently ignored by
every lookup, because `DtedTile.open` returns null for it. **An import that reports success is
not proof the data is readable.** The log line to confirm against is:

```
DtedIndex: loaded N/M DTED tile(s); finest post spacing 0.000278°
```

`N` is what parsed; `M` is what was imported. **If N is less than M, some tiles are not DTED.**

## 2. The file layout the parser expects

Offsets are from the start of the file. The parser reads the first 80 bytes and then seeks
straight into the data.

| Block | Length | Used? |
|---|---|---|
| **UHL** (User Header Label) | 80 bytes | **YES — every field below comes from here** |
| **DSI** (Data Set Identification) | 648 bytes | No, but the space MUST be present |
| **ACC** (Accuracy Description) | 2700 bytes | No, but the space MUST be present |
| Data records | to end of file | yes |

**Data starts at byte 3428** (80 + 648 + 2700). That offset is a constant in the code
(`DtedTile.DATA_START`). ⚠ **A file without the DSI and ACC blocks will parse its header and
then read garbage**, because the data offset is assumed, not computed.

### 2.1 The UHL fields that are read

| Bytes | Field | Format the parser accepts |
|---|---|---|
| 0–3 | Magic | **must be exactly `UHL1`** — a file that does not start with this is rejected |
| 4–11 | Origin longitude | `DDDMMSSH`, e.g. `1500000W`. See the tolerance note below |
| 12–19 | Origin latitude | `DDMMSSH`, e.g. `0610000N` |
| 20–23 | Longitude interval | tenths of an arc-second, as ASCII digits, e.g. `0010` = 1.0″ |
| 24–27 | Latitude interval | tenths of an arc-second, e.g. `0010` = 1.0″ |
| 47–50 | Number of longitude lines | ASCII digits, e.g. `3601` |
| 51–54 | Number of latitude points | ASCII digits, e.g. `3601` |

Everything else in the UHL is **not read** and may hold anything, but the bytes must be there.

⚠ **THE ORIGIN IS THE SOUTH-WEST CORNER**, and it is parsed leniently: the code takes the last
two digits as seconds, the two before as minutes, and **whatever remains as degrees**. So
`1500000W`, `150000W` and `01500000W` all give -150.0. A hemisphere letter `N`/`S`/`E`/`W` must
follow the digits. `S` and `W` make the value negative.

⚠ **The post intervals are TENTHS of an arc-second.** `0010` means 1.0 arc-second, not 10. A
value of `0001` would mean 0.1″ and would be accepted, producing a tile the application thinks
is ten times finer than DTED2.

## 3. The elevation data

```
for each longitude column, west to east:
    8 bytes   record header   (not read — any content)
    2 bytes   x nLat          elevation posts, SOUTH to NORTH
    4 bytes   checksum        (NOT CHECKED — any content)
```

**Record length = 12 + 2 x nLat bytes.** For a 3601-post tile that is 7214 bytes per column and
3601 columns, so **25,977,614 bytes of data** plus the 3428-byte header: a DTED2 tile is about
**26 MB**.

### 3.1 Post encoding — ⚠ NOT two's complement

Each post is **two bytes, big-endian, SIGN-MAGNITUDE**:

- bit 15 (the top bit of the first byte) is the **sign**: 1 means negative
- bits 14–0 are the **magnitude** in whole metres

```
value = ((b0 & 0x7F) << 8) | b1
if (b0 & 0x80) value = -value
```

⚠ **This is the single most common way a hand-built tile comes out wrong.** Two's complement is
not the same encoding: -1 is `0x8001` in DTED and `0xFFFF` in two's complement. A tile written
in two's complement reads as absurd positive heights below sea level and plausible-looking
rubbish elsewhere.

**Void posts are `-32767`** (`0xFFFF` in sign-magnitude). The application treats a void, an
unreadable post and an unopenable file identically: no elevation at that point. ⚠ **If ANY of
the four posts surrounding a query is void, the lookup returns nothing for that point** — it
does not interpolate around the hole. Fill your voids.

**Units are whole metres.** There is no scale factor.

## 4. Resolution, and why the application prefers the finest

| Level | Post spacing | Approximate ground spacing | Posts in a 1° tile |
|---|---|---|---|
| DTED0 | 30″ | ~900 m | 121 x 121 |
| DTED1 | 3″ | ~90 m | 1201 x 1201 |
| **DTED2** | **1″** | **~30 m** | **3601 x 3601** |

`DtedIndex` sorts tiles by post spacing and uses the **finest tile that covers the point**, so
importing DTED0 and DTED2 for the same cell is safe — the DTED2 wins.

⚠ **RESOLUTION IS NOT A COSMETIC CHOICE HERE, AND THERE IS A MEASUREMENT.** Marker placement
divides terrain error by the tangent of the look angle. At a 21° depression, **1 m of terrain
error becomes 2.6 m of horizontal miss**; at 54° it is 0.7 m. Measured 2026-08-01: drops at 54°
landed within a metre while drops at 21° were about 10 ft out. **Finer terrain is the single
most effective thing you can give this application for marker accuracy.**

⚠ **Nothing stops you supplying BETTER than DTED2.** The parser reads `nLon`/`nLat` and the
intervals from the header and does no validation against the standard levels, so a 0.5″ or
0.3″ tile would be read correctly **provided the header describes it honestly** and the data
block matches. Name it `.dt2`; the extension is only a filter, and the post spacing the
application sorts on comes from the header. ⚠ Untested — nobody has yet given it one.

## 5. ⚠ THE VERTICAL DATUM — read this before converting anything

**The application treats every elevation as metres above MEAN SEA LEVEL — specifically the
geoid, which for DTED means EGM96.** This is stated in `ElevationPolicy`, and the whole AR
overlay depends on it:

> DTED is height above MEAN SEA LEVEL (the geoid); a CoT contact carries `hae`, height above
> the WGS84 ELLIPSOID. MSL and HAE differ by the geoid separation N, **about +12 m in
> Anchorage**: `hae = msl + N`.

⚠ **MOST MODERN ELEVATION PRODUCTS ARE NOT IN THIS FRAME, AND THE ERROR IS SILENT.** If you
convert a source that is height above the WGS84 ellipsoid without subtracting the geoid
separation, every elevation is about 12 m too high in Anchorage and nothing in the application
will tell you. It will simply place markers wrong, by more than the resolution gain you were
trying to buy.

| Source | Native vertical datum | Conversion needed |
|---|---|---|
| SRTM, Copernicus DEM (GLO-30) | EGM96 / EGM2008 geoid | **none to little** — EGM96 is what DTED uses |
| USGS 3DEP (US) | NAVD88 | NAVD88 → EGM96 |
| LiDAR products, generally | often the ellipsoid, sometimes a local datum | **check, and convert** |
| ALOS AW3D30 | EGM96 | none |

**GDAL is the practical tool**, and the vertical shift is the part to get right rather than the
reprojection.

⚠ **The horizontal datum is WGS84** and the grid is geographic — plain degrees of latitude and
longitude, not a projected grid. A tile in UTM is not convertible by renaming.

## 6. Packaging and import

- Put the tiles in a **`.zip`**. Pre-Flight Setup, section 4 "Elevation Data (DTED)", the
  **Import Region** button.
- **One zip is one "region"** in the UI, named after the zip's filename with the extension
  removed. The pilot manages regions, not tiles.
- Folder structure inside the zip is allowed and is **FLATTENED**: `w150/n61.dt2` is stored as
  `w150_n61.dt2`. This is so the common `w150/n61.dt2` + `w149/n61.dt2` layout does not collide
  with itself.
- ⚠ **A tile with the same flattened name OVERWRITES the one already stored, with no prompt.**
  That is intended — re-importing a corrected region replaces it. It also means a flat zip of
  `n61.dt2` from two different longitude folders **loses one of them**. Keep the folders, or
  name the files uniquely.
- Tiles are shared between regions. Deleting a region deletes only the tiles no other region
  still references.

**Recommended naming**, which the common tools already produce:

```
anchorage-dted2.zip
  w150/n61.dt2
  w149/n61.dt2
  w150/n62.dt2
```

## 7. Checking that it worked

1. The region appears in Pre-Flight with a tile count and a size.
2. ⚠ **Read the log**, with file logging on. `DtedIndex` prints `loaded N/M DTED tile(s)` and
   the finest post spacing. **N must equal M**, and the spacing must be the one you built:
   `0.000278` for 1″, `0.000833` for 3″, `0.008333` for 30″.
3. On the flight screen the height readout changes from height above the takeoff point to true
   height above the ground, and the AR warning about flat-plane estimates goes.
4. Drop a pin on a feature whose true position you know and read the `SPI:` line, which gives
   the solved ground point to 7 decimals. ⚠ Any aim offsets in force (`aim=[pitch… brg…]` on
   that line) must be recorded or zeroed first — see the AR note in `CLAUDE.md`.

## 8. ⚠ What this will and will not improve

**It will improve**: marker and SPI placement, most of all at shallow look angles; the AGL
readout; the AR overlay's elevation for contacts with no height of their own.

**It will NOT improve** the other AR error sources, and they may dominate. The 2026-09-14 audit
left open: the telemetry-to-video lag, and the magnetometer bias (which the operator has
decided not to correct in the application). Better terrain cannot fix a bearing error.

⚠ **There is no evidence yet of how much of the current AR error is terrain.** Finer data is a
reasonable thing to try, but measure before and after — §7 step 4 is how.

## 9. Limits and things not implemented

- **No checksum validation.** The 4-byte record checksum is skipped entirely.
- **No DSI or ACC parsing.** Accuracy metadata in the file is not read, and the application
  cannot tell the pilot how good the data claims to be.
- **No size or count limit in code.** A DTED2 tile is ~26 MB; the practical limit is the
  controller's storage.
- **No coverage map.** The pilot sees region names and tile counts, not a map of what is
  covered. A lookup outside coverage returns nothing and the application falls back to the flat
  plane.
- **Voids are not filled or interpolated across.** See §3.1.
