#!/usr/bin/env bash
# seed-teleos-demos.sh — build the TELEOS-2 measured-data demo suite for demo@orbit.local.
#
# Imports the real TELEOS-2 WOD telemetry (1–7 Jan 2026) six times and shapes each copy
# into one demo scenario (see docs/measured-demos.md for the presenter walkthrough):
#   1. TELEOS-2 — flown week (measured orbit & attitude)
#   2. TELEOS-2 — co-launch neighbourhood (LUMELITE-4 & POEM-2)
#   3. TELEOS-2 — inspector rendezvous (corrected two-impulse)
#   4. TELEOS-2 — close-range ops (V-bar approach)
#   5. TELEOS-2 — inspection sensor & link budget (real attitude)
#   6. TELEOS-2 — approach dispersion (Monte Carlo)
#
# Deputies: #2 uses the real co-launch TLEs (LUMELITE-4/POEM-2) — honest neighbourhood
# geometry. #3–#6 use SYNTHETIC co-planar inspectors derived from the measured chief's
# own state (the same pattern as the five seeded synthetic demos, NORAD 99101+): by
# Jan 2026 vs today's catalog, near-equatorial RAAN drift (~9.5°/day) has scattered
# every real object's plane 9–19° away from the measured truth (R19), so a real-TLE
# chaser would need km/s-class cross-plane ΔV — physically real, but not an RPO demo.
# An inspector launched into the chief's plane is what a real mission would fly.
#
# Auth modes (auto-detected by probing GET /scenarios):
#   oidc (the overlay stack): mints a password-grant token as the `demo` user via the
#     dev-realm `orbit-cli` client, so every scenario is natively owned by demo.
#   stub (base compose):      builds as the dev user, then reassigns ownership + audit
#     attribution to demo@orbit.local via SQL (the scenarios then only show up in the
#     UI when the OIDC stack is up and you sign in as demo).
#
# Tunables (env):
#   API=http://127.0.0.1:8081        backend base URL
#   ISSUER=https://174.75.16.25:8443/realms/orbit   must equal OIDC_ISSUER_URI
#   WOD_CSV=/shared_folder/cjho/WOD_SID_-_12052026_1_7_Jan_2026.csv  (container path)
#   DEMO_USERNAME=demo DEMO_EMAIL=demo@orbit.local DEMO_PASSWORD=<from .env>
#   FORCE=1                          archive + rebuild scenarios that already exist
#   SENSOR_BORESIGHT=0,0,1           body-frame boresight of the demo imager (#5) —
#                                    iterate visually until the cone sweeps the deputy
#   TCA_FALLBACK=2026-01-04T00:00:00Z  used if the LUMELITE-4 TCA can't be discovered
#   DV_SANITY=500                    max sane hold ΔV (m/s) before falling back to a
#                                    glideslope in #4 (CW is stretched at ~76 km)
#
# Requires: curl, python3, docker compose (db + backend up). Idempotent: existing
# target scenarios are skipped unless FORCE=1.
set -euo pipefail
cd "$(dirname "$0")/.."

API=${API:-http://127.0.0.1:8081}
ISSUER=${ISSUER:-https://174.75.16.25:8443/realms/orbit}
WOD_CSV=${WOD_CSV:-/shared_folder/cjho/WOD_SID_-_12052026_1_7_Jan_2026.csv}
DEMO_USERNAME=${DEMO_USERNAME:-demo}
DEMO_EMAIL=${DEMO_EMAIL:-demo@orbit.local}
FORCE=${FORCE:-0}
SENSOR_BORESIGHT=${SENSOR_BORESIGHT:-0,0,1}
TCA_FALLBACK=${TCA_FALLBACK:-}
DV_SANITY=${DV_SANITY:-500}

CHIEF=56310     # TELEOS-2 (the measured craft)
LUMELITE=56309  # LUMELITE-4 (PSLV-C55 co-launch)
POEM=56308      # POEM-2    (PSLV-C55 co-launch)
DEV_ID=00000000-0000-0000-0000-000000000001

S1_NAME="TELEOS-2 — flown week (measured orbit & attitude)"
S2_NAME="TELEOS-2 — co-launch neighbourhood (LUMELITE-4 & POEM-2)"
S3_NAME="TELEOS-2 — inspector rendezvous (corrected two-impulse)"
S4_NAME="TELEOS-2 — close-range ops (V-bar approach)"
S5_NAME="TELEOS-2 — inspection sensor & link budget (real attitude)"
S6_NAME="TELEOS-2 — approach dispersion (Monte Carlo)"

# Collision-avoidance demo (US-MAN-12): a SYNTHETIC chief+intruder conjunction (not TELEOS
# measured data). The public API resolves roles from the catalog, and these NORAD ids aren't
# in it, so it is inserted directly via SQL (the same frozen-TLE body the per-user seeder builds
# internally — but owned by demo). This is the home for future curated feature demos too
# (see CLAUDE.md "Demos"): add a builder here and call it from main.
CAM_NAME="Demo — collision avoidance (conjunction)"

# Synthetic co-planar inspectors (one per RPO scenario, seeded-demo pattern).
INSP3=99101  # rendezvous chaser, ~100 km behind
INSP4=99102  # close-range craft,   ~5 km behind
INSP5=99103  # inspection target,  ~10 km behind
INSP6=99104  # dispersion chaser,  ~30 km behind
# The name importMeasured assigns before we rename (satellite name + data start date).
DEFAULT_IMPORT_NAME="TELEOS-2 (measured 2026-01-01)"

log()  { printf '\n=== %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

sql() { docker compose exec -T db psql -U orbit -d orbit -v ON_ERROR_STOP=1 -tA -c "$1"; }

# api METHOD PATH [JSON] → sets API_STATUS + API_BODY (never fails the script itself).
api() {
  local method=$1 path=$2 data=${3:-}
  local args=(-sS -w $'\n%{http_code}' -X "$method" "$API$path" -H 'Content-Type: application/json')
  [[ -n ${TOKEN:-} ]] && args+=(-H "Authorization: Bearer $TOKEN")
  [[ -n $data ]] && args+=(--data-binary "$data")
  local out
  out=$(curl "${args[@]}")
  API_STATUS=${out##*$'\n'}
  API_BODY=${out%$'\n'*}
}

expect() { # expect STATUS... — assert the last api() call landed on one of them
  local s
  for s in "$@"; do [[ $API_STATUS == "$s" ]] && return 0; done
  die "expected HTTP $* but got $API_STATUS: $(printf '%s' "$API_BODY" | head -c 500)"
}

jget() { printf '%s' "$API_BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# Robust UTC parse: tolerates 'Z' or no zone (OEM epochs) and any fractional-second width.
PYDATE='import datetime as dt, re
def p(s):
    s = re.sub(r"\.\d+", "", s.strip().replace("Z", "+00:00"))
    if "+" not in s:
        s += "+00:00"
    return dt.datetime.fromisoformat(s)
f = lambda t: t.strftime("%Y-%m-%dT%H:%M:%SZ")'

iso_add() { # iso_add ISO SECONDS
  python3 -c "$PYDATE
import sys
print(f(p(sys.argv[1]) + dt.timedelta(seconds=float(sys.argv[2]))))" "$1" "$2"
}

iso_clamp() { # iso_clamp ISO MIN MAX
  python3 -c "$PYDATE
import sys
t, lo, hi = (p(a) for a in sys.argv[1:4])
print(f(max(lo, min(hi, t))))" "$1" "$2" "$3"
}

# ---------------------------------------------------------------- auth + account

detect_mode() {
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "$API/scenarios") \
    || die "cannot reach $API — is the backend up?"
  case $code in
    200) MODE=stub ;;
    401) MODE=oidc ;;
    *) die "unexpected HTTP $code probing $API/scenarios" ;;
  esac
  log "auth mode: $MODE"
}

mint_token() {
  TOKEN=$(curl -sk -d grant_type=password -d client_id=orbit-cli \
      -d "username=$DEMO_USERNAME" -d "password=$DEMO_PASSWORD" -d "scope=openid email" \
      "$ISSUER/protocol/openid-connect/token" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token", ""))') || TOKEN=""
  [[ -n $TOKEN ]] || die "could not mint a token for '$DEMO_USERNAME' from $ISSUER.
Is the demo user + orbit-cli client in the realm? Apply deploy/keycloak/orbit-realm.json
(container recreate) or run the kcadm block in docs/measured-demos.md, and check
DEMO_PASSWORD in .env."
}

ensure_user() {
  sql "INSERT INTO users (id, email, roles)
       VALUES (gen_random_uuid(), '$DEMO_EMAIL',
               ARRAY['mission_planner','flight_dynamics_engineer']::TEXT[])
       ON CONFLICT (email) DO NOTHING;" >/dev/null
  DEMO_ID=$(sql "SELECT id FROM users WHERE email = '$DEMO_EMAIL';")
  [[ -n $DEMO_ID ]] || die "could not resolve the $DEMO_EMAIL user id"
  note "demo user id: $DEMO_ID"
}

# The account holds the TELEOS suite + curated feature demos (e.g. CAM_NAME) — archive the
# per-user ONBOARDING demos the seeder plants on first login (they stay available under
# maya/frank/gita/dev), but KEEP the curated demos this script owns here (CAM_NAME).
archive_synthetic_demos() {
  if [[ $MODE == oidc ]]; then
    api GET /scenarios; expect 200
    local ids id
    ids=$(printf '%s' "$API_BODY" | KEEP="$CAM_NAME" python3 -c '
import json, os, sys
keep = os.environ["KEEP"]
for s in json.load(sys.stdin):
    if s["name"].startswith("Demo — ") and s["name"] != keep:
        print(s["id"])')
    for id in $ids; do
      api DELETE "/scenarios/$id"; expect 200 204
      note "archived onboarding demo $id"
    done
  else
    sql "UPDATE scenarios SET deleted_at = now()
         WHERE owner_id = '$DEMO_ID' AND name LIKE 'Demo — %'
           AND name <> \$q\$$CAM_NAME\$q\$ AND deleted_at IS NULL;" >/dev/null
  fi
}

# Build the collision-avoidance demo (US-MAN-12): a synthetic chief (99001) + intruder (99005)
# on a flagged single close pass (~1.6 km, 3 km alert threshold), owned by demo. Inserted via SQL
# in BOTH modes (synthetic TLEs can't go through the catalog-resolving public API). Idempotent:
# skips a live copy unless FORCE=1; hard-deletes any prior copy (live or archived) before a
# rebuild, since UNIQUE(owner_id, name) is not partial (a soft-deleted row still holds the name).
build_cam_demo() {
  local start="2026-06-01T00:00:00Z" end="2026-06-01T03:00:00Z" epoch="2026-06-01T00:00:00.000Z"
  local existing
  existing=$(sql "SELECT id FROM scenarios
                  WHERE owner_id = '$DEMO_ID' AND name = \$q\$$CAM_NAME\$q\$ AND deleted_at IS NULL LIMIT 1;")
  if [[ -n $existing && $FORCE != 1 ]]; then
    note "SKIP: '$CAM_NAME' already exists ($existing) — set FORCE=1 to rebuild"
    return 0
  fi
  # Frozen synthetic TLEs (deterministic): chief circular ~518 km LEO; intruder same plane, a hair
  # lower/faster (15.205 vs 15.20 rev/day) and 0.1° behind → drifts up to a single ~1.6 km pass.
  local body
  body=$(EPOCH="$epoch" START="$start" END="$end" python3 -c '
import json, os
def role(rolename, norad, name, l1, l2):
    return {"role": rolename, "noradId": norad, "name": name,
            "initialState": {"kind": "tle",
                             "tle": {"line1": l1, "line2": l2, "epoch": os.environ["EPOCH"]},
                             "datasetId": None},
            "maneuvers": [], "sensors": [], "attitude": None, "constraints": []}
print(json.dumps({
    "schemaVersion": 6, "fidelity": "sgp4",
    "timeRange": {"start": os.environ["START"], "end": os.environ["END"]},
    "chief": role("chief", 99001, "DEMO CHIEF",
                  "1 99001U 26001A   26152.00000000  .00000000  00000-0  00000-0 0  9994",
                  "2 99001  51.6000   0.0000 0000100   0.0000   0.0000 15.20000000    13"),
    "deputies": [role("deputy", 99005, "DEMO INTRUDER (conjunction)",
                  "1 99005U 26004B   26152.00000000  .00000000  00000-0  00000-0 0  9991",
                  "2 99005  51.6000   0.0000 0000100   0.0000 359.9000 15.20500000    18")],
    "missDistanceThresholdM": 3000.0}))')
  # Clear any prior copy (frees the UNIQUE(owner,name) slot), then insert scenario + v1 + audit.
  # SEPARATE statements with pre-generated UUIDs (not sibling CTEs — data-modifying CTEs share one
  # snapshot and can't see each other's inserted rows, so a CTE UPDATE of latest_version_id would
  # match nothing). NULL latest_version_id at insert sidesteps the circular scenarios↔versions FK;
  # the follow-up UPDATE sets it once the version row exists.
  local sid vid
  sid=$(python3 -c 'import uuid; print(uuid.uuid4())')
  vid=$(python3 -c 'import uuid; print(uuid.uuid4())')
  sql "BEGIN;
    DELETE FROM scenarios WHERE owner_id = '$DEMO_ID' AND name = \$q\$$CAM_NAME\$q\$;
    INSERT INTO scenarios (id, owner_id, name, latest_version_id, created_at)
      VALUES ('$sid', '$DEMO_ID', \$q\$$CAM_NAME\$q\$, NULL, now());
    INSERT INTO scenario_versions (id, scenario_id, version_no, author_id, created_at, body)
      VALUES ('$vid', '$sid', 1, '$DEMO_ID', now(), \$body\$$body\$body\$::jsonb);
    UPDATE scenarios SET latest_version_id = '$vid' WHERE id = '$sid';
    INSERT INTO audit_log (id, scenario_id, version_id, actor_id, action, timestamp, diff_summary)
      VALUES (gen_random_uuid(), '$sid', '$vid', '$DEMO_ID', 'SEED', now(),
              'Seeded collision-avoidance demo (synthetic conjunction, US-MAN-12)');
    COMMIT;" >/dev/null
  note "collision-avoidance demo built (synthetic conjunction, owned by $DEMO_EMAIL, id $sid)"
}

# ------------------------------------------------------------- scenario plumbing

scenario_id_by_name() { # NAME → id (building identity's view) or empty
  if [[ $MODE == oidc ]]; then
    api GET /scenarios; expect 200
    printf '%s' "$API_BODY" | NAME="$1" python3 -c '
import json, os, sys
for s in json.load(sys.stdin):
    if s["name"] == os.environ["NAME"]:
        print(s["id"])
        break'
  else
    sql "SELECT id FROM scenarios
         WHERE owner_id IN ('$DEMO_ID', '$DEV_ID') AND name = \$q\$$1\$q\$
           AND deleted_at IS NULL LIMIT 1;"
  fi
}

archive_scenario() {
  if [[ $MODE == oidc ]]; then
    api DELETE "/scenarios/$1"; expect 200 204
  else
    # In stub mode a prior run may have reassigned the row to demo, so dev can't
    # API-delete it — soft-delete via SQL either way.
    sql "UPDATE scenarios SET deleted_at = now() WHERE id = '$1' AND deleted_at IS NULL;" >/dev/null
  fi
}

# guard NAME → 0 = proceed with a fresh build, 1 = keep the existing scenario
guard() {
  local existing
  existing=$(scenario_id_by_name "$1")
  [[ -z $existing ]] && return 0
  if [[ $FORCE == 1 ]]; then
    archive_scenario "$existing"
    note "FORCE=1: archived existing '$1' ($existing)"
    return 0
  fi
  note "SKIP: '$1' already exists ($existing) — set FORCE=1 to rebuild"
  return 1
}

# A live scenario still holding the import's default name (a crashed earlier run)
# would 409 the next import — rename it aside, never delete it.
displace_default_name() {
  local id
  id=$(scenario_id_by_name "$DEFAULT_IMPORT_NAME")
  [[ -z $id ]] && return 0
  warn "renaming aside a leftover '$DEFAULT_IMPORT_NAME' ($id)"
  rename_scenario "$id" "$DEFAULT_IMPORT_NAME (displaced $(date +%s))"
}

rename_scenario() { # id newname — a PUT that re-sends the current roster untouched
  api GET "/scenarios/$1"; expect 200
  local put
  put=$(printf '%s' "$API_BODY" | NEWNAME="$2" python3 -c '
import json, os, sys
b = json.load(sys.stdin)["body"]
print(json.dumps({
    "name": os.environ["NEWNAME"],
    "fidelity": b.get("fidelity") or "sgp4",
    "timeRange": {"start": b["timeRange"]["start"], "end": b["timeRange"]["end"]},
    "chief": {"noradId": b["chief"]["noradId"]},
    "deputies": [{"noradId": d["noradId"]} for d in (b.get("deputies") or [])],
}))')
  api PUT "/scenarios/$1" "$put"; expect 200
}

import_measured() { # → SC_ID, SPAN_START, SPAN_END
  displace_default_name
  api POST /scenarios/import/measured "{\"path\": \"$WOD_CSV\", \"noradId\": $CHIEF}"
  expect 201
  SC_ID=$(jget 'd["id"]')
  DEFAULT_IMPORT_NAME=$(jget 'd["name"]')
  SPAN_START=$(jget 'd["body"]["timeRange"]["start"]')
  SPAN_END=$(jget 'd["body"]["timeRange"]["end"]')
}

finalize() { # id name start end deputies-space-separated — the ONE roster/window PUT.
  # Re-sending chief=$CHIEF is what preserves the measured-ephemeris role (+ measured
  # attitude) through the update merge; maneuvers/sensors are authored only afterwards.
  local id=$1 name=$2 start=$3 end=$4 deps=${5:-}
  local put
  put=$(NAME="$name" START="$start" END="$end" DEPS="$deps" CHIEF_ID="$CHIEF" python3 -c '
import json, os
print(json.dumps({
    "name": os.environ["NAME"],
    "fidelity": "sgp4",
    "timeRange": {"start": os.environ["START"], "end": os.environ["END"]},
    "chief": {"noradId": int(os.environ["CHIEF_ID"])},
    "deputies": [{"noradId": int(n)} for n in os.environ["DEPS"].split()],
}))')
  api PUT "/scenarios/$id" "$put"; expect 200
  note "scenario ready: '$name' ($id) window $start … $end"
}

# ----------------------------------------------------- geometry from the OEM export

# discover_tca SCENARIO_ID DEPUTY_NORAD → TCA + MISS_KM of the chief↔deputy closest
# approach, computed from the CCSDS OEM export (the stream's own sampling grid — much
# cheaper than a full-catalog screening and deterministic per version).
discover_tca() {
  api GET "/scenarios/$1/export/oem"; expect 200
  local out
  out=$(printf '%s' "$API_BODY" | DEP="$2" python3 -c '
import math, os, sys
dep = os.environ["DEP"]
segs, cur, meta, inmeta = {}, None, {}, False
for line in sys.stdin:
    line = line.strip()
    if line == "META_START":
        inmeta, meta = True, {}
    elif line == "META_STOP":
        inmeta = False
        cur = meta.get("OBJECT_NAME", "?")
        segs.setdefault(cur, {})
    elif inmeta and "=" in line:
        k, v = line.split("=", 1)
        meta[k.strip()] = v.strip()
    elif cur and line and line[0].isdigit():
        p = line.split()
        if len(p) >= 7:
            segs[cur][p[0]] = (float(p[1]), float(p[2]), float(p[3]))
chief = next((n for n in segs if "TELEOS" in n.upper()), None)
target = next((n for n in segs if dep in ("LUMELITE-4", "POEM-2") and n.upper().startswith(dep.split("-")[0])), None)
if target is None:
    target = next((n for n in segs if n != chief), None)
if not chief or not target:
    sys.exit(f"could not find chief/deputy segments in the OEM (found: {list(segs)})")
best = None
for epoch, r1 in segs[chief].items():
    r2 = segs[target].get(epoch)
    if r2 is None:
        continue
    d = math.dist(r1, r2)
    if best is None or d < best[1]:
        best = (epoch, d)
if best is None:
    sys.exit("chief and deputy segments share no epochs")
print(best[0], round(best[1], 3), target)' 2>&1) || die "TCA discovery failed: $out"
  TCA=$(cut -d' ' -f1 <<<"$out")
  MISS_KM=$(cut -d' ' -f2 <<<"$out")
  TCA_SEGMENT=$(cut -d' ' -f3- <<<"$out")
}

# ------------------------------------------------- synthetic co-planar inspectors

# One python program, two modes (shared OEM parser):
#   gen    OEM TRAIL_KM NORAD          → "T0|LINE1|LINE2" — a TLE co-planar with the
#          chief's first OEM state, trailing TRAIL_KM in mean anomaly.
#   refine OEM TRAIL_KM NORAD          → same, but corrected by the chaser's measured
#          radial/in-track offset at T0 (one Newton step on a and M — the osculating-
#          as-mean TLE error is km-scale and mostly linear).
#   report OEM TRAIL_KM                → the chaser's RIC offset (km) at T0 and at the
#          window end, for the log.
INSPECTOR_PY='
import datetime as dt, math, re, sys

MU = 398600.4418  # km^3/s^2

def parse_iso(s):
    s = re.sub(r"\.\d+", "", s.strip().replace("Z", "+00:00"))
    if "+" not in s:
        s += "+00:00"
    return dt.datetime.fromisoformat(s)

def read_oem(path):
    segs, cur, meta, inmeta = {}, None, {}, False
    for line in open(path):
        line = line.strip()
        if line == "META_START":
            inmeta, meta = True, {}
        elif line == "META_STOP":
            inmeta = False
            cur = meta.get("OBJECT_NAME", "?")
            segs.setdefault(cur, [])
        elif inmeta and "=" in line:
            k, v = line.split("=", 1)
            meta[k.strip()] = v.strip()
        elif cur is not None and line and line[0].isdigit():
            p = line.split()
            if len(p) >= 7:
                segs[cur].append((p[0], tuple(float(x) for x in p[1:7])))
    return segs

def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def dot(a, b): return sum(x*y for x, y in zip(a, b))
def norm(a): return math.sqrt(dot(a, a))
def unit(a):
    n = norm(a)
    return tuple(x/n for x in a)
def clamp(x): return max(-1.0, min(1.0, x))

def coe(r, v):
    rmag, vmag = norm(r), norm(v)
    h = cross(r, v)
    n = (-h[1], h[0], 0.0)
    evec = tuple((vmag*vmag - MU/rmag)*r[k]/MU - dot(r, v)*v[k]/MU for k in range(3))
    e = norm(evec)
    a = 1.0 / (2.0/rmag - vmag*vmag/MU)
    i = math.acos(clamp(h[2]/norm(h)))
    raan = math.atan2(n[1], n[0]) % (2*math.pi)
    argp = math.acos(clamp(dot(n, evec)/(norm(n)*e)))
    if evec[2] < 0:
        argp = 2*math.pi - argp
    nu = math.acos(clamp(dot(evec, r)/(e*rmag)))
    if dot(r, v) < 0:
        nu = 2*math.pi - nu
    ecc_anom = 2*math.atan2(math.sqrt(1-e)*math.sin(nu/2), math.sqrt(1+e)*math.cos(nu/2))
    m_anom = (ecc_anom - e*math.sin(ecc_anom)) % (2*math.pi)
    return a, e, i, raan, argp, m_anom

def checksum(line):
    return sum(int(c) if c.isdigit() else 1 if c == "-" else 0 for c in line) % 10

def tle(norad, epoch, a, e, i, raan, argp, m_anom):
    # Back-step the TLE epoch 3 h before the window start (rewinding M by n·Δt),
    # for two reasons:
    # (1) a maneuvered deputy propagates numerically FROM ITS TLE EPOCH, and an
    #     ImpulseManeuver date detector exactly at the propagation start never
    #     fires — templates put the first burn at the scenario start;
    # (2) the stream samples a pre-window margin of PATH_PERIODS/2 ≈ 0.75 orbit
    #     (~72 min) — if the TLE epoch sits INSIDE that margin the stream drives
    #     the maneuvered propagator backward before crossing the burn forward,
    #     and the re-executed transfer lands ~100 m off the corrected plan
    #     (the Decision-24 stateful-propagator pathology). 3 h clears the margin.
    back = 10800.0
    epoch = epoch - dt.timedelta(seconds=back)
    m_anom = (m_anom - math.sqrt(MU/a**3)*back) % (2*math.pi)
    yy = epoch.year % 100
    doy = (epoch - dt.datetime(epoch.year, 1, 1, tzinfo=dt.timezone.utc)).total_seconds()/86400.0 + 1.0
    n_revday = 86400.0 / (2*math.pi*math.sqrt(a**3/MU))
    l1 = f"1 {norad:05d}U 26900A   {yy:02d}{doy:012.8f}  .00000000  00000-0  00000-0 0  999"
    l2 = (f"2 {norad:05d} {math.degrees(i):8.4f} {math.degrees(raan):8.4f} "
          f"{int(round(e*1e7)):07d} {math.degrees(argp):8.4f} {math.degrees(m_anom):8.4f} "
          f"{n_revday:11.8f}    1")
    return l1 + str(checksum(l1)), l2 + str(checksum(l2))

def ric_offset(chief0, chaser0):
    r_hat = unit(chief0[1][:3])
    w_hat = unit(cross(chief0[1][:3], chief0[1][3:]))
    i_hat = unit(cross(w_hat, r_hat))
    rel = tuple(chaser0[1][k] - chief0[1][k] for k in range(3))
    return dot(rel, r_hat), dot(rel, i_hat), dot(rel, w_hat)

mode, oem_path, trail_km = sys.argv[1], sys.argv[2], float(sys.argv[3])
segs = read_oem(oem_path)
chief_name = next((n for n in segs if "TELEOS" in n.upper()), None) or sys.exit("no chief segment")
chaser_name = next((n for n in segs if n != chief_name), None)
chief = segs[chief_name]
epoch = parse_iso(chief[0][0])
r, v = chief[0][1][:3], chief[0][1][3:]
a, e, i, raan, argp, m_anom = coe(r, v)

if mode == "report":
    chaser = segs[chaser_name]
    d0, dN = ric_offset(chief[0], chaser[0]), ric_offset(chief[-1], chaser[-1])
    print(f"t0 RIC ({d0[0]:+.2f}, {d0[1]:+.2f}, {d0[2]:+.2f}) km ... end RIC ({dN[0]:+.2f}, {dN[1]:+.2f}, {dN[2]:+.2f}) km")
    sys.exit(0)

# Corrections are CUMULATIVE across refine iterations (each rebuild starts from the
# fresh osculating elements): pass the previous totals in, get the new totals out.
da_km = float(sys.argv[5]) if len(sys.argv) > 5 else 0.0
dm_rad = float(sys.argv[6]) if len(sys.argv) > 6 else 0.0
if mode == "refine":
    # The osculating-as-mean TLE error shows up as (a) a period mismatch → secular
    # in-track drift, and (b) a phase offset. Correct the period from the MEASURED
    # drift over the window (ds/dt = -(3/2)·n·Δa) and the phase from the t0 offset.
    chaser = segs[chaser_name]
    d_r0, d_i0, _ = ric_offset(chief[0], chaser[0])
    _, d_iN, _ = ric_offset(chief[-1], chaser[-1])
    t_span = (parse_iso(chief[-1][0]) - parse_iso(chief[0][0])).total_seconds()
    n_rad = math.sqrt(MU / a**3)
    da_km += (2.0 / (3.0 * n_rad)) * ((d_iN - d_i0) / t_span)
    dm_rad += -(d_i0 + trail_km) / a  # ahead of the intended trail → slip M back
norad = int(sys.argv[4])
l1, l2 = tle(norad, epoch, a + da_km, e, i, raan, argp,
             (m_anom - trail_km/a + dm_rad) % (2*math.pi))
print(epoch.strftime("%Y-%m-%dT%H:%M:%SZ") + "|" + l1 + "|" + l2 + "|" + repr(da_km) + "|" + repr(dm_rad))
'

fetch_oem() { # id file
  api GET "/scenarios/$1/export/oem"; expect 200
  printf '%s' "$API_BODY" > "$2"
}

swap_deputy() { # scenario-id norad name line1 line2 epoch-iso — rewrites deputies[0]
  # in the latest version body. Post-roster-PUT only: any later roster PUT would
  # rebuild the deputy from the catalog and clobber this.
  sql "UPDATE scenario_versions SET body =
         jsonb_set(jsonb_set(jsonb_set(jsonb_set(jsonb_set(body,
           '{deputies,0,noradId}', to_jsonb($2::int)),
           '{deputies,0,name}', to_jsonb('$3'::text)),
           '{deputies,0,initialState,tle,line1}', to_jsonb('$4'::text)),
           '{deputies,0,initialState,tle,line2}', to_jsonb('$5'::text)),
           '{deputies,0,initialState,tle,epoch}', to_jsonb('$6'::text))
       WHERE id = (SELECT latest_version_id FROM scenarios WHERE id = '$1');" >/dev/null
}

place_inspector() { # scenario-id norad name trail-km — synthesize, refine twice, report
  local id=$1 norad=$2 name=$3 trail=$4 oem line t0 l1 l2 da=0 dm=0 pass
  oem=$(mktemp)
  fetch_oem "$id" "$oem"
  line=$(python3 -c "$INSPECTOR_PY" gen "$oem" "$trail" "$norad")
  IFS='|' read -r t0 l1 l2 da dm <<<"$line"
  swap_deputy "$id" "$norad" "$name" "$l1" "$l2" "$t0"
  for pass in 1 2; do
    fetch_oem "$id" "$oem"
    line=$(python3 -c "$INSPECTOR_PY" refine "$oem" "$trail" "$norad" "$da" "$dm")
    IFS='|' read -r t0 l1 l2 da dm <<<"$line"
    swap_deputy "$id" "$norad" "$name" "$l1" "$l2" "$t0"
  done
  fetch_oem "$id" "$oem"
  note "$name (norad $norad, ~${trail} km trailing): $(python3 -c "$INSPECTOR_PY" report "$oem" "$trail")"
  rm -f "$oem"
}

# Every maneuver template fires its first burn AT the scenario start — which is also
# the OEM/analysis grid's first point. The burn still fires (the forward propagation
# from the deputy's earlier seed epoch crosses it), but landing exactly on a grid
# point leaves a ~metre boundary-timing residual in the sampled trajectory. Trimming
# the window start back 47 s (not a multiple of the 30 s grid step) AFTER authoring
# puts the burn strictly inside a grid interval, so the closest-approach numbers read
# clean (sub-metre) in the table/graph/OEM. A cosmetic nicety, not a correctness fix —
# a normal (non-trimmed) scenario exports the burn fine, just ~metre-fuzzier at t0.
# SQL (not PUT) so the synthetic deputy + its maneuvers are preserved.
trim_window_start() { # scenario-id new-start-iso
  sql "UPDATE scenario_versions SET body =
         jsonb_set(body, '{timeRange,start}', to_jsonb('$2'::text))
       WHERE id = (SELECT latest_version_id FROM scenarios WHERE id = '$1');" >/dev/null
}

# ------------------------------------------------------------- maneuver authoring

insert_corrected_rendezvous() { # id deputy-norad min-arrival-iso → inserts the two burns
  api POST "/scenarios/$1/maneuvers/rendezvous/search" "{\"deputyNoradId\": $2}"
  expect 200
  local pick
  pick=$(printf '%s' "$API_BODY" | MINARR="$3" python3 -c "$PYDATE"'
import json, os, sys
d = json.load(sys.stdin)
minarr = p(os.environ["MINARR"])
cells = [c for c in d.get("cells") or [] if p(c["arrivalEpoch"]) >= minarr]
# Keep the flight time short: the differential corrector is a shooting method, and
# long/multi-rev transfers are ill-conditioned under J2 (nodal precession over
# several revs dwarfs the two-body plan and the corrector fails to converge). Take
# the cheapest cell arriving within an hour of the earliest allowed arrival.
band = [c for c in cells if p(c["arrivalEpoch"]) <= minarr + dt.timedelta(hours=1)]
c = (band or cells or [d.get("cheapest")])[0]  # cells are sorted cheapest-first
if not c:
    sys.exit("the rendezvous search returned no feasible cell")
print(f(p(c["arrivalEpoch"])), c["nRev"], round(c["totalDvMs"], 2))' 2>&1) \
    || die "rendezvous search failed: $pick"
  local arr nrev dv
  read -r arr nrev dv <<<"$pick"
  note "rendezvous plan: arrive $arr (nRev=$nrev, two-body seed ≈ $dv m/s)"
  api POST "/scenarios/$1/maneuvers/rendezvous" \
      "{\"deputyNoradId\": $2, \"arrivalEpoch\": \"$arr\", \"corrected\": true, \"nRev\": $nrev}"
  expect 200
}

total_dv_ms() { # of the last api() ScenarioResponse
  jget 'sum((m["deltaV"]["r"]**2 + m["deltaV"]["i"]**2 + m["deltaV"]["c"]**2) ** 0.5
            for dep in d["body"]["deputies"] for m in (dep.get("maneuvers") or []))'
}

clear_maneuvers() { # id
  api GET "/scenarios/$1"; expect 200
  local mids mid
  mids=$(jget '"\n".join(m["id"] for dep in d["body"]["deputies"] for m in (dep.get("maneuvers") or []))')
  for mid in $mids; do
    api DELETE "/scenarios/$1/maneuvers/$mid"; expect 200 204
  done
}

# Close-range ops (#4): a V-bar hold from the ~5 km-trailing inspector — squarely in
# the CW validity regime. If the solve still misbehaves, clear it and fall back to a
# glideslope walk-in. Swap the primary here if preferred.
insert_close_range_ops() { # id deputy-norad window-start
  local id=$1 dep=$2 arr
  arr=$(iso_add "$3" 2400)   # arrive 40 min in ≈ 0.42 orbit (avoids the CW integer-rev singularity)
  api POST "/scenarios/$id/maneuvers/hold" \
      "{\"deputyNoradId\": $dep, \"axis\": \"vbar\", \"distanceM\": -2000, \"arrivalEpoch\": \"$arr\"}"
  if [[ $API_STATUS == 200 ]]; then
    local dv
    dv=$(total_dv_ms)
    if python3 -c "import sys; sys.exit(0 if float('$dv') <= $DV_SANITY else 1)"; then
      note "V-bar hold at −2 km inserted (Σ|ΔV| ≈ $(printf '%.1f' "$dv") m/s, arrive $arr)"
      return 0
    fi
    warn "hold Σ|ΔV| ≈ $dv m/s exceeds DV_SANITY=$DV_SANITY — falling back to a glideslope"
    clear_maneuvers "$id"
  else
    warn "hold rejected (HTTP $API_STATUS) — falling back to a glideslope: $(printf '%s' "$API_BODY" | head -c 300)"
  fi
  api POST "/scenarios/$id/maneuvers/glideslope" \
      "{\"deputyNoradId\": $dep, \"axis\": \"vbar\", \"startRangeM\": -5000, \"endRangeM\": -500, \"closingRateMps\": 2, \"segments\": 4}"
  expect 200
  note "glideslope −5 km → −500 m inserted (Σ|ΔV| ≈ $(printf '%.1f' "$(total_dv_ms)") m/s)"
}

add_inspection_sensor() { # id
  local bx by bz
  IFS=',' read -r bx by bz <<<"$SENSOR_BORESIGHT"
  api POST "/scenarios/$1/sensors" "{\"noradId\": $CHIEF, \"kind\": \"optical\",
      \"name\": \"Inspection imager\", \"fovType\": \"cone\", \"halfAngleDeg\": 60,
      \"hDeg\": 0, \"vDeg\": 0, \"minRangeM\": 1, \"maxRangeM\": 200000,
      \"boresightX\": $bx, \"boresightY\": $by, \"boresightZ\": $bz, \"clockDeg\": 0}"
  expect 200
  local sensor_id
  sensor_id=$(jget 'd["body"]["chief"]["sensors"][-1]["id"]')
  api PUT "/scenarios/$1/sensors/$sensor_id/link-budget" \
      '{"kind": "rf", "eirpDbw": 10, "gOverTdbK": 3, "frequencyGhz": 2.25, "bandwidthHz": 1000000, "thresholdDb": 6}'
  expect 200
  api POST "/scenarios/$1/constraints" "{\"hostNoradId\": $CHIEF, \"kind\": \"sun-keep-out\",
      \"sensorId\": \"$sensor_id\", \"targetNoradId\": 0, \"limitDeg\": 25, \"rangeM\": 0}"
  expect 200
  note "imager '$sensor_id' (boresight body [$SENSOR_BORESIGHT], 60° cone, ≤200 km) + RF link budget + 25° sun-keep-out"
}

# ------------------------------------------------------------------- verification

verify_rendezvous_audit() { # id label — the corrector must have CONVERGED
  api GET "/scenarios/$1/audit"; expect 200
  local out
  out=$(printf '%s' "$API_BODY" | python3 -c '
import json, re, sys
for e in json.load(sys.stdin):
    if e["action"] in ("MANEUVER_TEMPLATE", "MANEUVER_ADD") \
            and "rendezvous" in (e["diffSummary"] or "").lower():
        m = re.search(r"arrival miss ([0-9.]+) m\)", e["diffSummary"])
        if m and "Corrected" in e["diffSummary"]:
            print(m.group(1))
        else:
            sys.exit("corrector did not converge: " + e["diffSummary"])
        break
else:
    sys.exit("no rendezvous maneuver audit entry found")' 2>&1) \
    || die "$2: $out"
  python3 -c "import sys; sys.exit(0 if float('$out') < 100 else 1)" \
    || die "$2: corrected arrival miss ${out} m ≥ 100 m"
  note "$2: corrector converged, arrival miss ${out} m"
}

verify_oem_closure() { # id arrival-window-center — chief↔deputy min range < 1 km near arrival
  discover_tca "$1" "LUMELITE-4"
  python3 -c "import sys; sys.exit(0 if float('$MISS_KM') < 1.0 else 1)" \
    || die "OEM cross-check: post-burn min range ${MISS_KM} km ≥ 1 km (segment '$TCA_SEGMENT')"
  note "OEM cross-check: post-burn closest approach ${MISS_KM} km at $TCA"
}

verify_monte_carlo() { # id deputy-norad
  note "Monte Carlo smoke run (16 samples — the real one is run live in the UI)…"
  api POST "/scenarios/$1/monte-carlo" \
      "{\"deputyNoradId\": $2, \"sampleCount\": 16, \"seed\": 1, \"posSigmaM\": 50, \"velSigmaMs\": 0.05, \"dvMagFrac\": 0.02, \"dvPointingDeg\": 1}"
  if [[ $API_STATUS == 422 ]] && printf '%s' "$API_BODY" | grep -q "TLE"; then
    die "Monte Carlo still rejects the measured chief — rebuild the backend:
  docker compose build backend && docker compose -f docker-compose.yml -f docker-compose.oidc.yml up -d backend"
  fi
  expect 200
  local n
  n=$(jget 'len(d["tracks"])')
  [[ $n -gt 0 ]] || die "Monte Carlo returned no tracks"
  note "Monte Carlo OK ($n tracks, $(jget 'len(d["ellipsoids"])') covariance ellipsoids)"
}

# In stub mode the demos were built as dev — hand them (and their versions, audit rows
# and datasets) to demo@orbit.local in one transaction.
reassign_to_demo() {
  local names="\$q\$$S1_NAME\$q\$, \$q\$$S2_NAME\$q\$, \$q\$$S3_NAME\$q\$, \$q\$$S4_NAME\$q\$, \$q\$$S5_NAME\$q\$, \$q\$$S6_NAME\$q\$"
  sql "BEGIN;
    UPDATE scenarios SET owner_id = '$DEMO_ID'
      WHERE owner_id = '$DEV_ID' AND deleted_at IS NULL AND name IN ($names);
    UPDATE scenario_versions SET author_id = '$DEMO_ID'
      WHERE scenario_id IN (SELECT id FROM scenarios WHERE owner_id = '$DEMO_ID' AND name IN ($names));
    UPDATE audit_log SET actor_id = '$DEMO_ID'
      WHERE scenario_id IN (SELECT id FROM scenarios WHERE owner_id = '$DEMO_ID' AND name IN ($names));
    UPDATE measured_dataset SET owner_id = '$DEMO_ID'
      WHERE id IN (SELECT DISTINCT (sv.body #>> '{chief,initialState,datasetId}')::uuid
                     FROM scenario_versions sv
                     JOIN scenarios s ON s.id = sv.scenario_id
                    WHERE s.owner_id = '$DEMO_ID' AND s.name IN ($names)
                      AND sv.body #>> '{chief,initialState,datasetId}' IS NOT NULL);
    COMMIT;" >/dev/null
  note "ownership + audit attribution reassigned to $DEMO_EMAIL"
}

# ==================================================================== main

command -v curl >/dev/null || die "curl is required"
command -v python3 >/dev/null || die "python3 is required"
command -v docker >/dev/null || die "docker is required (for psql via the db container)"

detect_mode
ensure_user
if [[ $MODE == oidc ]]; then
  : "${DEMO_PASSWORD:=$(grep -E '^DEMO_PASSWORD=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)}"
  [[ -n ${DEMO_PASSWORD:-} ]] || die "DEMO_PASSWORD not set (env or .env)"
  mint_token
fi
archive_synthetic_demos

# --- #1 flown week (also the span reference for everything else)
log "#1 $S1_NAME"
if guard "$S1_NAME"; then
  import_measured
  S1=$SC_ID
  finalize "$S1" "$S1_NAME" "$SPAN_START" "$SPAN_END" ""
else
  S1=$(scenario_id_by_name "$S1_NAME")
  api GET "/scenarios/$S1"; expect 200
  SPAN_START=$(jget 'd["body"]["timeRange"]["start"]')
  SPAN_END=$(jget 'd["body"]["timeRange"]["end"]')
fi

# --- #2 co-launch neighbourhood: built at full span first so its OEM reveals the TCA
log "#2 $S2_NAME"
if guard "$S2_NAME"; then
  import_measured
  S2=$SC_ID
  finalize "$S2" "$S2_NAME" "$SPAN_START" "$SPAN_END" "$LUMELITE $POEM"
  S2_FRESH=1
else
  S2=$(scenario_id_by_name "$S2_NAME")
  S2_FRESH=0
fi

if [[ $S2_FRESH == 1 ]]; then
  # Center the window on the LUMELITE-4 closest approach (only #2 needs the TCA).
  if [[ -n $TCA_FALLBACK ]]; then
    TCA=$TCA_FALLBACK MISS_KM="(TCA_FALLBACK override)"
    warn "using TCA_FALLBACK=$TCA"
  else
    note "discovering the LUMELITE-4 closest approach from the OEM export…"
    discover_tca "$S2" "LUMELITE-4"
    note "TCA $TCA, miss ≈ $MISS_KM km (deputy TLEs are illustrative — R19; this drifts as the catalog refreshes)"
  fi
  finalize "$S2" "$S2_NAME" "$(iso_clamp "$(iso_add "$TCA" -43200)" "$SPAN_START" "$SPAN_END")" \
                            "$(iso_clamp "$(iso_add "$TCA" 43200)" "$SPAN_START" "$SPAN_END")" "$LUMELITE $POEM"
  api PUT "/scenarios/$S2/miss-distance" '{"missDistanceThresholdM": 100000}'; expect 200
  note "conjunction miss-distance threshold set to 100 km"
fi

# The RPO scenarios (#3–#6) don't depend on the TCA — they fly a synthetic co-planar
# inspector against the measured chief. Anchor them two days into the data span.
T0=$(iso_add "$SPAN_START" 172800)

# --- #3 inspector rendezvous
log "#3 $S3_NAME"
[[ $MODE == oidc ]] && mint_token
if guard "$S3_NAME"; then
  import_measured
  S3=$SC_ID
  finalize "$S3" "$S3_NAME" "$T0" "$(iso_add "$T0" 21600)" "$LUMELITE"
  place_inspector "$S3" "$INSP3" "INSPECTOR-1 (hypothetical)" 100
  insert_corrected_rendezvous "$S3" "$INSP3" "$(iso_add "$T0" 5400)"
  trim_window_start "$S3" "$(iso_add "$T0" -47)"
  verify_rendezvous_audit "$S3" "#3 rendezvous"
  verify_oem_closure "$S3"
else
  S3=$(scenario_id_by_name "$S3_NAME")
fi

# --- #4 close-range ops
log "#4 $S4_NAME"
[[ $MODE == oidc ]] && mint_token
if guard "$S4_NAME"; then
  import_measured
  S4=$SC_ID
  finalize "$S4" "$S4_NAME" "$T0" "$(iso_add "$T0" 14400)" "$LUMELITE"
  place_inspector "$S4" "$INSP4" "INSPECTOR-2 (hypothetical)" 5
  insert_close_range_ops "$S4" "$INSP4" "$T0"
  trim_window_start "$S4" "$(iso_add "$T0" -47)"
else
  S4=$(scenario_id_by_name "$S4_NAME")
fi

# --- #5 inspection sensor & link budget
log "#5 $S5_NAME"
[[ $MODE == oidc ]] && mint_token
if guard "$S5_NAME"; then
  import_measured
  S5=$SC_ID
  finalize "$S5" "$S5_NAME" "$T0" "$(iso_add "$T0" 21600)" "$LUMELITE"
  place_inspector "$S5" "$INSP5" "INSPECTOR-3 (hypothetical)" 10
  add_inspection_sensor "$S5"
else
  S5=$(scenario_id_by_name "$S5_NAME")
fi

# --- #6 approach dispersion (short window keeps the live Monte Carlo run snappy)
log "#6 $S6_NAME"
[[ $MODE == oidc ]] && mint_token
if guard "$S6_NAME"; then
  import_measured
  S6=$SC_ID
  finalize "$S6" "$S6_NAME" "$T0" "$(iso_add "$T0" 10800)" "$LUMELITE"
  place_inspector "$S6" "$INSP6" "INSPECTOR-4 (hypothetical)" 30
  insert_corrected_rendezvous "$S6" "$INSP6" "$(iso_add "$T0" 5400)"
  trim_window_start "$S6" "$(iso_add "$T0" -47)"
  verify_rendezvous_audit "$S6" "#6 rendezvous"
  verify_monte_carlo "$S6" "$INSP6"
else
  S6=$(scenario_id_by_name "$S6_NAME")
fi

[[ $MODE == stub ]] && reassign_to_demo

# --- collision-avoidance demo (synthetic conjunction; SQL-owned by demo in both modes)
log "CAM $CAM_NAME"
build_cam_demo

log "done — the demo suite for $DEMO_EMAIL"
printf '  %s\t%s\n' "$S1" "$S1_NAME" "$S2" "$S2_NAME" "$S3" "$S3_NAME" \
                    "$S4" "$S4_NAME" "$S5" "$S5_NAME" "$S6" "$S6_NAME" \
                    "(synthetic)" "$CAM_NAME"
cat <<EOF

Next (manual, in a browser): sign in at https://174.75.16.25:8443/ as ${DEMO_USERNAME}
(cert warning is expected — self-signed) and walk docs/measured-demos.md. If the #5
FOV cone never sweeps INSPECTOR-3, re-run with FORCE=1 SENSOR_BORESIGHT=<x,y,z>.
EOF
