# Deploying Builds to the Test Phone (Syncthing)

This document describes how to get a freshly built APK (and its QNN model
package) onto the **Galaxy S23 Ultra** test device. Deployment is done by
dropping files into the **Syncthing** folder — the phone then receives them
automatically over the local network. No adb, no USB, no manual `scp` needed.

---

## The one rule that matters: the drop location

Syncthing runs **inside a podman container** (`systemd-syncthing`), and its
`/var/syncthing` is a **named volume** — *not* the host's `/var/syncthing`.

| Path | Is it watched by Syncthing? |
|---|---|
| `/var/syncthing/s23` (host) | ❌ **No.** This is a red herring — files here are invisible to Syncthing. |
| **`/var/lib/containers/storage/volumes/syncthing-data/_data/s23/`** | ✅ **Yes.** This is the real synced folder. |

**Always copy deployment artifacts here:**

```bash
/var/lib/containers/storage/volumes/syncthing-data/_data/s23/
```

### Why a volume?

The container's mounts are:

| Container path | Backed by |
|---|---|
| `/var/syncthing` | named volume `syncthing-data` (this is the synced folder) |
| `/syncthing` | bind mount of host `/home/gc/syncthing` (currently *not* used by the folder config) |

The `s23` folder config (`ss6ek-hhrv7`) points at `/var/syncthing/s23`, which
resolves to the volume. If you ever want to sync from the host home directory
instead, edit the container's `config.xml` to point the folder at
`/syncthing/s23` and restart the container — then `/home/gc/syncthing/s23/`
becomes the drop path.

---

## Step-by-step: deploy a fresh APK

### 1. Build the APK

From inside the toolchain container (distrobox `android-dbx`):

```bash
cd ~/android-keyboard
./gradlew assembleUnstableDebug
```

Output lands at:

```
~/android-keyboard/build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
```

### 2. Copy it into the synced folder

The volume's parent directories are root-owned, so use `sudo`:

```bash
sudo cp \
  /home/gc/android-keyboard/build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk \
  /var/lib/containers/storage/volumes/syncthing-data/_data/s23/
```

### 3. Fix ownership so Syncthing can read it

Syncthing runs as `gc` inside the container. Make sure the file is readable
(and owned by `gc` for cleanliness):

```bash
sudo chown gc:gc \
  /var/lib/containers/storage/volumes/syncthing-data/_data/s23/android-keyboard-unstable-debug.apk
```

> ⚠️ `sudo cp` leaves the file owned by `root`. A `root`-owned file with mode
> `644` is still *readable* by Syncthing, but `chown` keeps things consistent
> and avoids permission surprises later.

### 4. Confirm Syncthing picked it up

Wait a few seconds (large files need hashing), then check the folder status:

```bash
sudo podman exec systemd-syncthing \
  sh -c 'curl -s http://localhost:8384/rest/db/status?folder=ss6ek-hhrv7 | grep -oE "\"(state|globalFiles|localFiles|inSyncFiles)\":[^,]+"'
```

You want to see:

```
"state":"idle"        (or "scanning" during the initial hash)
"globalFiles":3
"localFiles":3
"inSyncFiles":3
```

When `globalFiles == localFiles == inSyncFiles`, everything is indexed and
syncing to the phone.

### 5. Install on the phone

The file appears in the Syncthing folder on the S23 (inside Syncthing's
configured sync directory). Open it there and install — or move it to
`Downloads` via Termux first:

```bash
# On the phone (Termux), if you need it in Downloads:
termux-setup-storage        # once
cp ~/storage/<syncthing-dir>/android-keyboard-unstable-debug.apk ~/storage/downloads/
termux-open ~/storage/downloads/android-keyboard-unstable-debug.apk
```

---

## Deploying the QNN model package

The hardware-acceleration path needs the **`precompiled_qnn_onnx`** model
package (contains the `.onnx` graphs *and* their `_qairt_context.bin`
siblings — the bare `qnn_context_binary` package will **not** work).

Correct package for the S23 Ultra (Snapdragon 8 Gen 2 / SM8550 / HTP v73):

```
whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-qcs8550-proxy.zip   (859.8 MB)
```

Deploy it the same way:

```bash
sudo cp <path-to-zip> \
  /var/lib/containers/storage/volumes/syncthing-data/_data/s23/
sudo chown gc:gc \
  /var/lib/containers/storage/volumes/syncthing-data/_data/s23/*.zip
```

Then, **inside the app**: Languages → (locale) → Voice Input → **Import from
file** → select the ZIP. The app extracts `encoder.onnx` + `decoder.onnx`
(+ their context binaries) and registers the model.

---

## Current deployed files (as of this writing)

| File | Size | Purpose |
|---|---|---|
| `android-keyboard-unstable-debug.apk` | 248 MB | The app build (QNN + Vulkan + CPU engines) |
| `whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-qcs8550-proxy.zip` | 859.8 MB | QNN model package for the S23 Ultra |
| `touched.txt` | 0 B | Syncthing connectivity probe |
| `.stfolder/` | — | Syncthing folder marker (do not delete) |

---

## Troubleshooting

**"Files aren't showing up on the phone"**
→ You probably copied them to the host `/var/syncthing/s23` instead of the
volume path. The host path is **not** mounted into the container. Use the
volume path above.

**"File is there but Syncthing isn't syncing"**
→ Check the folder status (step 4). If `state` is stuck, the file may be
`root`-owned — `chown gc:gc` it. If the container isn't running, start it:
`sudo podman start systemd-syncthing`.

**"The ZIP won't import in the app"**
→ Confirm you're using the `precompiled_qnn_onnx` package (not
`qnn_context_binary`). The import field requires `encoder.onnx` /
`decoder.onnx` entries. Also ensure the file picker shows `.zip` files (fixed
in the current build — the importer now accepts `application/zip`).

---

## Quick reference

```bash
# The ONLY path that matters for deployment:
DEPLOY_DIR=/var/lib/containers/storage/volumes/syncthing-data/_data/s23/

# Build → copy → chown → verify
./gradlew assembleUnstableDebug
sudo cp build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk "$DEPLOY_DIR"
sudo chown gc:gc "$DEPLOY_DIR/android-keyboard-unstable-debug.apk"
sudo podman exec systemd-syncthing sh -c \
  'curl -s http://localhost:8384/rest/db/status?folder=ss6ek-hhrv7'
```
