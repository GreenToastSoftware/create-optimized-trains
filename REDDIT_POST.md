# [MOD] Create Optimized Trains — Fix train stuttering and chunk-boundary stops on large networks (1.20.1 Forge)

Hey r/CreateMod!

If you've ever built a large train network and noticed your trains **freezing for half a second every time they cross a chunk boundary**, or **jerking back and forth at stations**, this mod is for you.

---

## What's the problem?

Create's train system has a built-in safety mechanism: if the chunk ahead of a train isn't fully loaded (entity-ticking status), it sets `speed = 0` and waits. On small networks this is fine. On large networks with 5+ trains running simultaneously, it causes:

- **Stutter every ~16 blocks** as trains cross chunk boundaries
- **Trains briefly stopping and reversing** when a carriage loses its entity
- **False derailing** when an entity is briefly missing at a chunk border
- **Slow chunk loading** because all trains compete for the same force-load cap

---

## What does this mod do?

**Create Optimized Trains** replaces that stop-and-wait behaviour with a proactive system:

**Core fixes:**
- ✅ Trains **never stop** waiting for chunks — they continue moving while chunks load in the background
- ✅ **Smart chunk pre-loading** with directional lookahead based on speed (faster train = more chunks pre-loaded ahead)
- ✅ **Priority loading** for the train the player is riding — bypasses global cap, uses track graph position instead of entity (no chicken-and-egg problem)
- ✅ **Anti-derailing immunity** — suppresses false stress spikes when a carriage entity is briefly missing at a chunk boundary
- ✅ **Ghost mode** for distant trains — trains far from any player continue running via the track graph without using the force-load cap, freeing it entirely for trains near the player
- ✅ **Route preloading** — chunks along the upcoming route are loaded into memory before the train arrives

**Compatibility:**
- ✅ Distant Horizons — detects DH and adjusts behaviour accordingly
- ✅ Works alongside other Create addons
- ✅ Configurable via `createoptimizedtrains-common.toml`

---

## Results

Before: trains on a 17-carriage network would stutter every 1–2 seconds, with repeated `carriageWaitingForChunks` stops and false derailing on a map with ~12 concurrent trains.

After: trains run smoothly through the full route. The force-load cap is now used efficiently — trains near the player get full chunk coverage, distant trains run in ghost mode without wasting resources.

---

## Details

- **Minecraft:** 1.20.1
- **Loader:** Forge 47.x
- **Requires:** Create 6.0.8 + Flywheel 1.0.5 (bundled)
- **Version:** 1.3.2

**GitHub:** https://github.com/GreenToastSoftware/create-optimized-trains

---

## FAQ

**Does this break vanilla Create train behaviour?**
No. Train routing, signals, schedules and collisions all work as normal. The mod only changes *how chunks are loaded* and *prevents the stop-wait loop*.

**Is it server-side only?**
The chunk loading and physics fixes are server-side. Some client-side rendering optimisations are also included but optional.

**Will it work with my existing world?**
Yes — no world changes needed. Just drop the jar in your mods folder.

**Does it affect TPS?**
The overhead is minimal. The main thread work per tick is O(trains_near_player), not O(all_trains). Ghost mode ensures distant trains don't add to the per-tick cost.

---

Happy to answer questions! If you test it on your network, let me know how it goes — always looking for feedback on edge cases.
